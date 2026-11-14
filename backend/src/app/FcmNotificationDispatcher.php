<?php

namespace App;

use Illuminate\Support\Facades\Http;
use RuntimeException;

final class FcmNotificationDispatcher
{
    private const MAX_ATTEMPTS = 8;
    private const CLAIM_LEASE_SECONDS = 600;

    public function __construct(private readonly FcmHttpV1Client $fcm)
    {
    }

    /**
     * Claim due notification/token deliveries atomically, then send them through
     * FCM. Inbox read state is not used as the delivery-work ledger.
     *
     * @return array{notifications:int,tokens:int,sent:int,invalid_tokens:int,failed:int,skipped:int}
     */
    public function dispatch(?int $limit = null): array
    {
        $this->assertConfigured();
        $limit = max(1, min($limit ?? (int) config('tacru.fcm_batch_limit', 100), 500));

        $response = $this->client()->post($this->rpcUrl('api_claim_fcm_delivery_batch'), [
            'p_limit' => $limit,
            'p_lease_seconds' => self::CLAIM_LEASE_SECONDS,
        ]);
        if (!$response->successful() || !is_array($response->json())) {
            throw new RuntimeException('Supabase FCM delivery queue claim failed.');
        }

        $attempts = array_values(array_filter($response->json(), 'is_array'));
        $notificationIds = [];
        foreach ($attempts as $attempt) {
            $id = (int) ($attempt['notification_id'] ?? 0);
            if ($id > 0) {
                $notificationIds[$id] = true;
            }
        }

        $stats = [
            'notifications' => count($notificationIds),
            'tokens' => count($attempts),
            'sent' => 0,
            'invalid_tokens' => 0,
            'failed' => 0,
            'skipped' => 0,
        ];

        foreach ($attempts as $attempt) {
            $attemptId = (int) ($attempt['attempt_id'] ?? 0);
            $notificationId = (int) ($attempt['notification_id'] ?? 0);
            $token = $attempt['token'] ?? null;
            $attemptCount = max(1, (int) ($attempt['attempt_count'] ?? 1));
            if ($attemptId < 1 || $notificationId < 1 || !is_string($token) || $token === '') {
                $stats['skipped']++;
                continue;
            }

            $data = [
                'notification_id' => (string) $notificationId,
                'event_type' => (string) ($attempt['event_type'] ?? 'incident_status_changed'),
            ];
            if (isset($attempt['incident_id']) && $attempt['incident_id'] !== null) {
                $data['incident_id'] = (string) $attempt['incident_id'];
            }

            try {
                $result = $this->fcm->send(
                    (string) config('tacru.fcm_project_id'),
                    $token,
                    $data,
                );

                if ($result['sent']) {
                    $this->updateAttempt($attemptId, 'sent', $result['provider_message_name'], null);
                    $stats['sent']++;
                } elseif ($result['invalid_token']) {
                    // FCM UNREGISTERED is the definitive signal that this token is
                    // no longer registered. Do not delete tokens for INVALID_ARGUMENT.
                    $this->deleteToken($token);
                    $this->updateAttempt($attemptId, 'invalid_token', null, $result['error_code']);
                    $stats['invalid_tokens']++;
                } elseif ($result['retryable']) {
                    $this->updateAttempt(
                        $attemptId,
                        'failed',
                        null,
                        $result['error_code'],
                        $this->nextAttemptAt(
                            $attemptCount,
                            (int) $result['status'],
                            $result['retry_after_seconds'],
                        ),
                    );
                    $stats['failed']++;
                } else {
                    $this->updateAttempt(
                        $attemptId,
                        'permanent_failure',
                        null,
                        $result['error_code'],
                    );
                    $stats['failed']++;
                }
            } catch (\Throwable) {
                // Transport or ledger-update failures retain the attempt for a
                // bounded retry. A worker crash leaves the claim leased for 10 min.
                $this->updateAttempt(
                    $attemptId,
                    'failed',
                    null,
                    'TRANSPORT_FAILURE',
                    $this->nextAttemptAt($attemptCount, 0, null),
                );
                $stats['failed']++;
            }
        }

        return $stats;
    }

    /**
     * Validate server configuration and ADC without claiming or sending a push.
     */
    public function checkConfiguration(): void
    {
        $this->assertConfigured();
        $this->fcm->assertCredentialsAvailable();
    }

    private function assertConfigured(): void
    {
        if (trim((string) config('tacru.supabase_url')) === '' || trim((string) config('tacru.supabase_secret_key')) === '') {
            throw new RuntimeException('FCM dispatcher requires the server-only Supabase secret key.');
        }
        if (trim((string) config('tacru.fcm_project_id')) === '') {
            throw new RuntimeException('FCM dispatcher requires FCM_PROJECT_ID.');
        }
    }

    private function updateAttempt(
        int $id,
        string $status,
        ?string $providerName,
        ?string $errorCode,
        ?string $nextAttemptAt = null,
    ): void {
        $this->patchAttempt($id, [
            'status' => $status,
            'provider_message_name' => $providerName,
            'last_error_code' => $errorCode,
            'next_attempt_at' => $nextAttemptAt ?? now()->toISOString(),
            'updated_at' => now()->toISOString(),
        ]);
    }

    private function patchAttempt(int $id, array $payload): void
    {
        $query = http_build_query(
            ['id' => 'eq.'.$id],
            '',
            '&',
            PHP_QUERY_RFC3986,
        );
        $response = $this->client()->patch($this->tableUrl('fcm_delivery_attempts').'?'.$query, $payload);
        if (!$response->successful()) {
            throw new RuntimeException('Supabase FCM delivery ledger update failed.');
        }
    }

    private function deleteToken(string $token): void
    {
        // Laravel's delete(url, data) argument is a request body, not PostgREST
        // query parameters. Put the encoded filter in the URL instead.
        $query = http_build_query(
            ['token' => 'eq.'.$token],
            '',
            '&',
            PHP_QUERY_RFC3986,
        );
        $response = $this->client()->delete($this->tableUrl('fcm_tokens').'?'.$query);
        if (!$response->successful()) {
            throw new RuntimeException('Supabase invalid-token cleanup failed.');
        }
    }

    private function nextAttemptAt(int $attemptCount, int $httpStatus, ?int $retryAfterSeconds): string
    {
        // FCM recommends at least a one-minute initial delay for quota errors.
        $baseSeconds = $httpStatus === 429 ? 60 : 2;
        $exponent = min(10, max(0, $attemptCount - 1));
        $backoff = min(3600, $baseSeconds * (2 ** $exponent));
        $jitter = random_int(0, max(1, (int) floor($backoff * 0.25)));
        $providerDelay = max(0, min(86400, $retryAfterSeconds ?? 0));

        return now()->addSeconds(max($providerDelay, $backoff + $jitter))->toISOString();
    }

    private function client()
    {
        return Http::acceptJson()
            ->asJson()
            ->withHeaders([
                'apikey' => (string) config('tacru.supabase_secret_key'),
                'Authorization' => 'Bearer '.(string) config('tacru.supabase_secret_key'),
            ])
            ->timeout(max(1, (int) config('tacru.fcm_http_timeout', 8)));
    }

    private function tableUrl(string $table): string
    {
        return rtrim((string) config('tacru.supabase_url'), '/').'/rest/v1/'.$table;
    }

    private function rpcUrl(string $function): string
    {
        return rtrim((string) config('tacru.supabase_url'), '/').'/rest/v1/rpc/'.$function;
    }
}
