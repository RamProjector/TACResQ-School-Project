<?php

namespace App;

/**
 * Build server-side Supabase API-key headers without treating opaque sb_* keys
 * as JWT bearer tokens. Legacy service_role keys remain JWTs and need both
 * headers for compatibility.
 */
final class SupabaseServerHeaders
{
    /**
     * @param array<string, string> $additionalHeaders
     * @return array<string, string>
     */
    public static function forSecretKey(string $secretKey, array $additionalHeaders = []): array
    {
        $headers = ['apikey' => $secretKey] + $additionalHeaders;

        // Modern Supabase secret keys are opaque API keys, not JWTs. Send them
        // only via apikey. Legacy service_role keys are JWTs and retain the
        // Authorization header until the project finishes key migration.
        if (!str_starts_with($secretKey, 'sb_secret_')) {
            $headers['Authorization'] = 'Bearer '.$secretKey;
        }

        return $headers;
    }
}
