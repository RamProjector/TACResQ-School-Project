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
                ->timeout(5)
                ->get($baseUrl.'/auth/v1/user');

            $status = $response->status();
            $user = $response->successful() ? $response->json() : null;
            if (is_array($user) && is_string($user['id'] ?? null) && Str::isUuid($user['id'])) {
                request()->attributes->set('auth_user_id', $user['id']);
            }

            app(ApiOperationAuditLogger::class)->record(
                'supabase',
                'auth.get_user',
                $status >= 500 || $status === 429 ? 'failure' : ($response->successful() ? 'success' : 'rejected'),
                $status,
                (int) ((hrtime(true) - $startedAt) / 1_000_000),
                null,
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
                null,
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

    private function correlationId(): string
    {
        $value = request()->attributes->get('correlation_id');
        return is_string($value) && Str::isUuid($value) ? $value : (string) Str::uuid();
    }
}
