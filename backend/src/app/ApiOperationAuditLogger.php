<?php

namespace App;

use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Str;
use Throwable;

final class ApiOperationAuditLogger
{
    /**
     * Persist metadata-only telemetry with the server-side Supabase secret.
     * Logging is fail-open: an audit outage must not break an operational API
     * response, and exception text/URLs/payloads are never copied to logs.
     */
    public function record(
        string $provider,
        string $operation,
        string $outcome,
        ?int $httpStatus,
        int $durationMs,
        ?string $providerRequestId = null,
        ?string $errorCode = null,
        ?string $correlationId = null,
    ): void {
        $baseUrl = rtrim((string) config('tacru.supabase_url'), '/');
        $secret = trim((string) config('tacru.supabase_secret_key'));
        if ($baseUrl === '' || $secret === '') {
            return;
        }

        $correlationId ??= request()->attributes->get('correlation_id');
        if (!is_string($correlationId) || !Str::isUuid($correlationId)) {
            $correlationId = (string) Str::uuid();
        }

        $actorAuthUserId = request()->attributes->get('auth_user_id');
        if (!is_string($actorAuthUserId) || !Str::isUuid($actorAuthUserId)) {
            $actorAuthUserId = null;
        }

        $payload = [
            'correlation_id' => $correlationId,
            'actor_auth_user_id' => $actorAuthUserId,
            'provider' => $provider,
            'operation' => $operation,
            'source' => 'render_api',
            'outcome' => $outcome,
            'http_status' => $httpStatus,
            'duration_ms' => max(0, $durationMs),
            'provider_request_id' => $providerRequestId !== null ? mb_substr($providerRequestId, 0, 255) : null,
            'error_code' => $errorCode !== null ? mb_substr($errorCode, 0, 80) : null,
        ];

        try {
            $headers = [
                'apikey' => $secret,
                'Prefer' => 'return=minimal',
            ];
            // Legacy service_role keys are JWTs and need Authorization to
            // establish the Postgres role. Modern sb_secret keys are not JWTs;
            // Supabase requires them on apikey only.
            if (!str_starts_with($secret, 'sb_secret_')) {
                $headers['Authorization'] = 'Bearer '.$secret;
            }

            $response = Http::acceptJson()
                ->asJson()
                ->withHeaders($headers)
                ->connectTimeout(1)
                ->timeout(2)
                ->post($baseUrl.'/rest/v1/api_operation_logs', $payload);

            if (!$response->successful()) {
                Log::warning('TACResQ API operation audit write failed', [
                    'provider' => $provider,
                    'operation' => $operation,
                    'correlation_id' => $correlationId,
                    'http_status' => $response->status(),
                ]);
            }
        } catch (Throwable $exception) {
            Log::warning('TACResQ API operation audit write failed', [
                'provider' => $provider,
                'operation' => $operation,
                'correlation_id' => $correlationId,
                'error_type' => $exception::class,
            ]);
        }
    }
}
