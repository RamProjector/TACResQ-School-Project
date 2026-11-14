<?php

namespace Tests\Unit;

use App\FcmHttpV1ErrorClassifier;
use PHPUnit\Framework\TestCase;

final class FcmHttpV1ErrorClassifierTest extends TestCase
{
    public function test_unregistered_fcm_detail_invalidates_token_even_with_not_found_status(): void
    {
        $result = FcmHttpV1ErrorClassifier::classify(404, [
            'status' => 'NOT_FOUND',
            'details' => [[
                '@type' => 'type.googleapis.com/google.firebase.fcm.v1.FcmError',
                'errorCode' => 'UNREGISTERED',
            ]],
        ]);

        self::assertTrue($result['invalid_token']);
        self::assertFalse($result['retryable']);
        self::assertSame('UNREGISTERED', $result['error_code']);
    }

    public function test_bad_request_field_violation_does_not_delete_token_for_invalid_argument(): void
    {
        $result = FcmHttpV1ErrorClassifier::classify(400, [
            'status' => 'INVALID_ARGUMENT',
            'details' => [[
                '@type' => 'type.googleapis.com/google.rpc.BadRequest',
                'fieldViolations' => [[
                    'field' => 'message.data.notification_id',
                    'description' => 'The value must be a string.',
                ]],
            ]],
        ]);

        self::assertFalse($result['invalid_token']);
        self::assertFalse($result['retryable']);
        self::assertSame('INVALID_ARGUMENT', $result['error_code']);
    }

    public function test_typed_fcm_invalid_argument_marks_registration_invalid(): void
    {
        $result = FcmHttpV1ErrorClassifier::classify(400, [
            'status' => 'INVALID_ARGUMENT',
            'details' => [[
                '@type' => 'type.googleapis.com/google.firebase.fcm.v1.FcmError',
                'errorCode' => 'INVALID_ARGUMENT',
            ]],
        ]);

        self::assertTrue($result['invalid_token']);
        self::assertFalse($result['retryable']);
    }

    public function test_quota_exceeded_is_retryable(): void
    {
        $result = FcmHttpV1ErrorClassifier::classify(429, [
            'status' => 'QUOTA_EXCEEDED',
        ]);

        self::assertFalse($result['invalid_token']);
        self::assertTrue($result['retryable']);
    }

    public function test_unavailable_is_retryable(): void
    {
        $result = FcmHttpV1ErrorClassifier::classify(503, [
            'status' => 'UNAVAILABLE',
        ]);

        self::assertFalse($result['invalid_token']);
        self::assertTrue($result['retryable']);
    }
}
