<?php

namespace App;

use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;
use Throwable;

final class SupabaseBridge
{
    /**
     * Validate the access token with Supabase Auth. Never decode an unverified
     * JWT or trust client-supplied role claims.
     */
    public function getUser(string $accessToken): ?array
    {
        $baseUrl = rtrim((string) config('tacru.supabase_url'), '/');
        $publishableKey = (string) config('tacru.supabase_publishable_key');

        if ($baseUrl === '' || $publishableKey === '' || trim($accessToken) === '') {
            return null;
        }

        $startedAt = hrtime(true);
        try {
            $response = Http::acceptJson()
                ->withHeaders([
                    'apikey' => $publishableKey,
                    'Authorization' => 'Bearer '.$accessToken,
                ])
                ->connectTimeout(2)
                ->timeout(5)
                ->get($baseUrl.'/auth/v1/user');

            $status = $response->status();
            $user = $response->successful() ? $response->json() : null;
            if (is_array($user) && is_string($user['id'] ?? null) && Str::isUuid($user['id'])) {
                request()->attributes->set('auth_user_id', $user['id']);
                // Auth /user has validated this exact bearer token. Only now
                // decode its signed session_id claim, and require its sub to
                // match the verified Auth response before recording it.
                request()->attributes->set(
                    'auth_session_id',
                    $this->sessionIdFromVerifiedToken($accessToken, $user['id']),
                );
            }

            app(ApiOperationAuditLogger::class)->record(
                'supabase',
                'auth.get_user',
                $status >= 500 || $status === 429 ? 'failure' : ($response->successful() ? 'success' : 'rejected'),
                $status,
                (int) ((hrtime(true) - $startedAt) / 1_000_000),
                $this->providerRequestId($response),
                null,
                $this->correlationId(),
            );

            if ($status >= 500 || $status === 429) {
                throw new \RuntimeException('Supabase Auth is temporarily unavailable.');
            }

            if (!$response->successful()) {
                return null;
            }

            return is_array($user) && is_string($user['id'] ?? null) ? $user : null;
        } catch (\RuntimeException $exception) {
            throw $exception;
        } catch (Throwable) {
            app(ApiOperationAuditLogger::class)->record(
                'supabase',
                'auth.get_user',
                'timeout',
                502,
                (int) ((hrtime(true) - $startedAt) / 1_000_000),
                null,
                'upstream_unavailable',
                $this->correlationId(),
            );
            throw new \RuntimeException('Supabase Auth is temporarily unavailable.');
        }
    }

    /**
     * Forward an allowlisted RPC using the caller's own JWT. No service-role
     * credential is used for the business operation; auth.uid() remains the
     * actual caller. A separate server-only credential writes audit metadata.
     *
     * @return array{status:int, body:mixed}
     */
    public function callRpc(string $accessToken, string $function, array $arguments): array
    {
        $allowlist = config('tacru.bridge_rpc_allowlist', []);
        if (!in_array($function, $allowlist, true)) {
            // Record rejected bridge RPC attempts without trusting arbitrary path
            // text as an operation name or forwarding it to PostgREST.
            $operation = preg_match('/^[A-Za-z][A-Za-z0-9_]{0,63}$/D', $function) === 1
                ? 'rpc.'.$function
                : 'rpc.invalid_name';
            app(ApiOperationAuditLogger::class)->record(
                'supabase',
                $operation,
                'rejected',
                404,
                0,
                null,
                'rpc_not_allowlisted',
                $this->correlationId(),
            );

            return ['status' => 404, 'body' => ['message' => 'RPC endpoint not found.']];
        }

        $baseUrl = rtrim((string) config('tacru.supabase_url'), '/');
        $publishableKey = (string) config('tacru.supabase_publishable_key');
        $startedAt = hrtime(true);

        try {
            $response = Http::acceptJson()
                ->asJson()
                ->withHeaders([
                    'apikey' => $publishableKey,
                    'Authorization' => 'Bearer '.$accessToken,
                ])
                ->connectTimeout(2)
                ->timeout(8)
                // PostgREST RPC parameters must be a JSON object; PHP's empty
                // array would serialize as [], not the no-argument object.
                ->post($baseUrl.'/rest/v1/rpc/'.$function, $arguments === [] ? new \stdClass() : $arguments);

            $status = $response->status();
            app(ApiOperationAuditLogger::class)->record(
                'supabase',
                'rpc.'.$function,
                $status >= 500 || $status === 429 ? 'failure' : ($status >= 400 ? 'rejected' : 'success'),
                $status,
                (int) ((hrtime(true) - $startedAt) / 1_000_000),
                $this->providerRequestId($response),
                null,
                $this->correlationId(),
            );

            return ['status' => $status, 'body' => $response->json()];
        } catch (Throwable) {
            app(ApiOperationAuditLogger::class)->record(
                'supabase',
                'rpc.'.$function,
                'timeout',
                502,
                (int) ((hrtime(true) - $startedAt) / 1_000_000),
                null,
                'upstream_unavailable',
                $this->correlationId(),
            );
            return ['status' => 502, 'body' => ['message' => 'Supabase is temporarily unavailable.']];
        }
    }

    /**
     * Supabase API Gateway returns sb-request-id on responses; it maps to the
     * provider log request_id and is the stable cross-service join key. The
     * x-request-id fallback supports self-hosted/reverse-proxy deployments.
     * Never accept a caller-supplied value for this provider identifier.
     */
    private function providerRequestId(\Illuminate\Http\Client\Response $response): ?string
    {
        $value = $response->header('sb-request-id');
        if (!is_string($value) || trim($value) === '') {
            $value = $response->header('x-request-id');
        }
        if (!is_string($value)) {
            return null;
        }

        $value = trim(preg_replace('/[\\x00-\\x1F\\x7F]/', '', $value) ?? '');
        return $value === '' ? null : mb_substr($value, 0, 255);
    }

    /**
     * Extract the signed Auth session claim only after /auth/v1/user has
     * successfully validated the bearer token. This does not verify the JWT
     * locally and never trusts role/profile claims from its payload.
     */
    private function sessionIdFromVerifiedToken(string $accessToken, string $verifiedUserId): ?string
    {
        $parts = explode('.', $accessToken);
        if (count($parts) !== 3) {
            return null;
        }

        $payload = strtr($parts[1], '-_', '+/');
        $padding = strlen($payload) % 4;
        if ($padding !== 0) {
            $payload .= str_repeat('=', 4 - $padding);
        }

        $decoded = base64_decode($payload, true);
        if (!is_string($decoded)) {
            return null;
        }

        try {
            $claims = json_decode($decoded, true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return null;
        }

        if (!is_array($claims)
            || !is_string($claims['sub'] ?? null)
            || !hash_equals($verifiedUserId, $claims['sub'])) {
            return null;
        }

        $sessionId = $claims['session_id'] ?? null;
        return is_string($sessionId) && Str::isUuid($sessionId) ? $sessionId : null;
    }

    private function correlationId(): string
    {
        $value = request()->attributes->get('correlation_id');
        return is_string($value) && Str::isUuid($value) ? $value : (string) Str::uuid();
    }
}
