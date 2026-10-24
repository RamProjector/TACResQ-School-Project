<?php

namespace App;

use Illuminate\Support\Facades\Http;
use Throwable;

final class SupabaseBridge
{
    /**
     * Ask Supabase Auth to validate the access token. We deliberately do not
     * decode an unverified JWT or trust role claims supplied by the client.
     */
    public function getUser(string $accessToken): ?array
    {
        $baseUrl = rtrim((string) config('tacru.supabase_url'), '/');
        $publishableKey = (string) config('tacru.supabase_publishable_key');

        if ($baseUrl === '' || $publishableKey === '' || trim($accessToken) === '') {
            return null;
        }

        try {
            $response = Http::acceptJson()
                ->withHeaders([
                    'apikey' => $publishableKey,
                    'Authorization' => 'Bearer '.$accessToken,
                ])
                ->timeout(5)
                ->get($baseUrl.'/auth/v1/user');

            if ($response->status() >= 500 || $response->status() === 429) {
                throw new \RuntimeException('Supabase Auth is temporarily unavailable.');
            }

            if (!$response->successful()) {
                return null;
            }

            $user = $response->json();

            return is_array($user) && is_string($user['id'] ?? null)
                ? $user
                : null;
        } catch (\RuntimeException $exception) {
            throw $exception;
        } catch (Throwable) {
            throw new \RuntimeException('Supabase Auth is temporarily unavailable.');
        }
    }

    /**
     * Forward an allowlisted RPC using the caller's own JWT. No service-role
     * credential is accepted or used; auth.uid() remains the actual caller.
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

        try {
            $response = Http::acceptJson()
                ->asJson()
                ->withHeaders([
                    'apikey' => $publishableKey,
                    'Authorization' => 'Bearer '.$accessToken,
                ])
                ->timeout(8)
                // PostgREST RPC parameters must be a JSON object; PHP's empty
                // array would serialize as [], which is not the no-argument shape.
                ->post($baseUrl.'/rest/v1/rpc/'.$function, $arguments === [] ? new \\stdClass() : $arguments);

            return ['status' => $response->status(), 'body' => $response->json()];
        } catch (Throwable) {
            return ['status' => 502, 'body' => ['message' => 'Supabase is temporarily unavailable.']];
        }
    }
}
