<?php

namespace Tests\Unit;

use App\SupabaseServerHeaders;
use PHPUnit\Framework\TestCase;

final class SupabaseServerHeadersTest extends TestCase
{
    public function test_modern_secret_key_is_sent_only_in_the_apikey_header(): void
    {
        $headers = SupabaseServerHeaders::forSecretKey(
            'sb'.chr(95).'secret'.chr(95).'synthetic-key',
            ['Prefer' => 'return=minimal'],
        );

        self::assertSame('sb'.chr(95).'secret'.chr(95).'synthetic-key', $headers['apikey']);
        self::assertSame('return=minimal', $headers['Prefer']);
        self::assertArrayNotHasKey('Authorization', $headers);
    }

    public function test_legacy_service_role_jwt_retains_bearer_compatibility(): void
    {
        $legacyJwt = 'synthetic.legacy.service-role.jwt';
        $headers = SupabaseServerHeaders::forSecretKey($legacyJwt);

        self::assertSame($legacyJwt, $headers['apikey']);
        self::assertSame('Bearer '.$legacyJwt, $headers['Authorization']);
    }
}
