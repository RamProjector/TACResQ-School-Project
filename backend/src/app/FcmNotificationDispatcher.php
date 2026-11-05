<?php

namespace App;

use Illuminate\Support\Facades\Http;
use RuntimeException;

final class FcmNotificationDispatcher
{
    public function __construct(private readonly FcmHttpV1Client $fcm)
    {
    }

    /**
     * Dispatch unread inbox events to the recipient's currently registered tokens.
     * The service-role key is required here and is never accepted from a request.
     *
     * @return array{notifications:int,tokens:int,sent:int,invalid_tokens:int,failed:int,skipped:int}
     */
    public function dispatch(?int $limit = null): array
    {
        $this->assertConfigured();
        $limit = max(1, min($limit ?? (int) config('tacru.fcm_batch_limit', 100), 500));
        $notifications = $this->get('user_notifications', [
            'select' => 'id,recipient_user_id,incident_id,event_type',
            'read_at' => 'is.null',
            'order' => 'id.asc',
            'limit' => $limit,
        ]);
        if ($notifications === []) {
            return ['notifications' => 0, 'tokens' => 0, 'sent' => 0, 'invalid_tokens' => 0, 'failed' => 0, 'skipped' => 0];
        }

        $profileIds = array_values(array_unique(array_map(
            static fn (array $notification): int => (int) ($notification['recipient_user_id'] ?? 0),
            $notifications,
        )));
        $profileIds = array_values(array_filter($profileIds, static fn (int $id): bool => $id > 0));
        $tokens = $this->get('fcm_tokens', [
            'select' => 'profile_id,token',
            'profile_id' => 'in.('.implode(',', $profileIds).')',
            'limit' => 2000,
        ]);
        $tokensByProfile = [];
        foreach ($tokens as $token) {
            $profileId = (int) ($token['profile_id'] ?? 0);
            $value = $token['token'] ?? null;
            if ($profileId > 0 && is_string($value) && strlen($value) >= 20 && strlen($value) <= 4096) {
                $tokensByProfile[$profileId][] = $value;
            }
        }

        $stats = ['notifications' => count($notifications), 'tokens' => 0, 'sent' => 0, 'invalid_tokens' => 0, 'failed' => 0, 'skipped' => 0];
        foreach ($notifications as $notification) {
            $notificationId = (int) ($notification['id'] ?? 0);
            $profileId = (int) ($notification['recipient_user_id'] ?? 0);
            if ($notificationId < 1 || $profileId < 1) {
                continue;
            }
            foreach ($tokensByProfile[$profileId] ?? [] as $token) {
                $stats['tokens']++;
                $attempt = $this->claimAttempt($notificationId, $profileId, $token);
                if ($attempt === null) {
                    $stats['skipped']++;
                    continue;
                }

                $data = [
                    'notification_id' => (string) $notificationId,
                    'event_type' => (string) ($notification['event_type'] ?? 'incident_status_changed'),
                ];
                if (isset($notification['incident_id']) && $notification['incident_id'] !== null) {
                    $data['incident_id'] = (string) $notification['incident_id'];
                }

                try {
                    $result = $this->fcm->send((string) config('tacru.fcm_project_id'), $token, $data);
                    if ($result['sent']) {
                        $this->updateAttempt((int) $attempt['id'], 'sent', $result['provider_message_name'], null);
                        $stats['sent']++;
                    } elseif ($result['invalid_token']) {
                        $this->deleteToken($token);
                        $this->updateAttempt((int) $attempt['id'], 'invalid_token', null, $result['error_code']);
                        $stats['invalid_tokens']++;
                    } else {
                        $this->updateAttempt((int) $attempt['id'], 'failed', null, $result['error_code']);
                        $stats['failed']++;
                    }
                } catch (\Throwable) {
                    $this->updateAttempt((int) $attempt['id'], 'failed', null, 'TRANSPORT_FAILURE');
                    $stats['failed']++;
                }
            }
        }

        return $stats;
    }

    private function assertConfigured(): void
    {
        if (trim((string) config('tacru.supabase_url')) === '' || trim((string) config('tacru.supabase_service_role_key')) === '') {
            throw new RuntimeException('FCM dispatcher requires the server-only Supabase service-role key.');
        }
        if (trim((string) config('tacru.fcm_project_id')) === '') {
            throw new RuntimeException('FCM dispatcher requires FCM_PROJECT_ID.');
        }
    }

    private function get(string $table, array $query): array
    {
        $response = $this->client()->get($this->tableUrl($table), $query);
        if (!$response->successful() || !is_array($response->json())) {
            throw new RuntimeException('Supabase push-dispatch read failed.');
        }
        return array_values(array_filter($response->json(), 'is_array'));
    }

    /** @return array{id:int}|null */
    private function claimAttempt(int $notificationId, int $profileId, string $token): ?array
    {
        $existing = $this->get('fcm_delivery_attempts', [
            'select' => 'id,status,attempted_at',
            'notification_id' => 'eq.'.$notificationId,
            'token' => 'eq.'.rawurlencode($token),
            'limit' => 1,
        ])[0] ?? null;
        if (is_array($existing)) {
            $status = (string) ($existing['status'] ?? '');
            if (in_array($status, ['sent', 'invalid_token'], true)) {
                return null;
            }
            if ($status === 'claimed' && (string) ($existing['attempted_at'] ?? '') >= now()->subMinutes(10)->toISOString()) {
                return null;
            }
            $this->patchAttempt((int) $existing['id'], ['status' => 'claimed', 'updated_at' => now()->toISOString(), 'attempted_at' => now()->toISOString()]);
            return ['id' => (int) $existing['id']];
        }

        $response = $this->client()
            ->withHeaders(['Prefer' => 'return=representation,resolution=ignore-duplicates'])
            ->post($this->tableUrl('fcm_delivery_attempts'), [
                'notification_id' => $notificationId,
                'profile_id' => $profileId,
                'token' => $token,
                'status' => 'claimed',
            ]);
        if (!$response->successful()) {
            return null;
        }
        $row = $response->json()[0] ?? null;
        return is_array($row) && isset($row['id']) ? ['id' => (int) $row['id']] : null;
    }

    private function updateAttempt(int $id, string $status, ?string $providerName, ?string $errorCode): void
    {
        $this->patchAttempt($id, [
            'status' => $status,
            'provider_message_name' => $providerName,
            'last_error_code' => $errorCode,
            'updated_at' => now()->toISOString(),
        ]);
    }

    private function patchAttempt(int $id, array $payload): void
    {
        $this->client()->patch(
            $this->tableUrl('fcm_delivery_attempts').'?id=eq.'.rawurlencode((string) $id),
            $payload,
        );
    }

    private function deleteToken(string $token): void
    {
        $this->client()->delete($this->tableUrl('fcm_tokens'), ['token' => 'eq.'.rawurlencode($token)]);
    }

    private function client()
    {
        return Http::acceptJson()
            ->asJson()
            ->withHeaders([
                'apikey' => (string) config('tacru.supabase_service_role_key'),
                'Authorization' => 'Bearer '.(string) config('tacru.supabase_service_role_key'),
            ])
            ->timeout(max(1, (int) config('tacru.fcm_http_timeout', 8)));
    }

    private function tableUrl(string $table): string
    {
        return rtrim((string) config('tacru.supabase_url'), '/').'/rest/v1/'.$table;
    }
}
