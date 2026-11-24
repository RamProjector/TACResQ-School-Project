<?php

namespace Tests\Feature;

use App\Contracts\FcmClientContract;
use App\FcmNotificationDispatcher;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use InvalidArgumentException;
use Mockery;
use Tests\TestCase;

final class FcmNotificationDispatcherTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();

        config([
            'tacru.supabase_url' => 'https://project.supabase.co',
            'tacru.supabase_secret_key' => 'test-server-key',
            'tacru.fcm_project_id' => 'tacresq-test',
            'tacru.fcm_batch_limit' => 50,
        ]);
    }

    public function test_missing_server_configuration_fails_before_queue_claim(): void
    {
        Http::fake();
        config(['tacru.supabase_secret_key' => '']);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldNotReceive('assertCredentialsAvailable');
        $fcm->shouldNotReceive('send');

        try {
            (new FcmNotificationDispatcher($fcm))->dispatch(1);
            self::fail('Expected missing server configuration to fail closed.');
        } catch (\RuntimeException $exception) {
            self::assertSame('FCM dispatcher requires the server-only Supabase secret key.', $exception->getMessage());
        }

        Http::assertNothingSent();
    }

    public function test_claim_service_failure_aborts_before_any_send(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response(['message' => 'unavailable'], 503),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        try {
            (new FcmNotificationDispatcher($fcm))->dispatch(1);
            self::fail('Expected queue claim failure to abort dispatch.');
        } catch (\RuntimeException $exception) {
            self::assertSame('Supabase FCM delivery queue claim failed.', $exception->getMessage());
        }
    }

    public function test_configured_batch_limit_is_clamped_to_database_cap(): void
    {
        $claimedLimit = null;
        config(['tacru.fcm_batch_limit' => 500]);
        Http::fake(function ($request) use (&$claimedLimit) {
            if (str_contains($request->url(), '/rpc/api_claim_fcm_delivery_batch')) {
                $claimedLimit = $request->data()['p_limit'] ?? null;
                return Http::response([], 200);
            }

            return Http::response([], 204);
        });

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch();

        self::assertSame(50, $claimedLimit);
        self::assertSame(0, $stats['tokens']);
    }

    public function test_invalid_explicit_limit_is_rejected_before_queue_claim(): void
    {
        Http::fake();

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        $dispatcher = new FcmNotificationDispatcher($fcm);

        try {
            $dispatcher->dispatch(0);
            self::fail('Expected invalid dispatch limit to be rejected.');
        } catch (InvalidArgumentException $exception) {
            self::assertSame('FCM dispatch limit must be between 1 and 50.', $exception->getMessage());
        }

        Http::assertNothingSent();
    }

    public function test_successful_send_is_written_to_delivery_ledger(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([$this->attempt()], 200),
            'https://project.supabase.co/rest/v1/fcm_delivery_attempts*' => Http::response([], 204),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->with('tacresq-test', 'device-token-a', [
            'notification_id' => '701',
            'recipient_profile_id' => '91',
            'event_type' => 'incident_status_changed',
            'incident_id' => '44',
        ])->andReturn($this->sentResult());

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['sent']);
        self::assertSame(0, $stats['failed']);
        Http::assertSent(fn ($request) => str_contains($request->url(), '/fcm_delivery_attempts')
            && $request->method() === 'PATCH'
            && ($request->data()['status'] ?? null) === 'sent');
    }

    public function test_ledger_failure_for_one_attempt_does_not_stop_later_recipients(): void
    {
        $patchCount = 0;
        Http::fake(function ($request) use (&$patchCount) {
            if (str_contains($request->url(), '/rpc/api_claim_fcm_delivery_batch')) {
                return Http::response([$this->attempt(801), $this->attempt(802, 'device-token-b')], 200);
            }
            if (str_contains($request->url(), '/fcm_delivery_attempts')) {
                $patchCount++;
                return Http::response([], $patchCount === 1 ? 500 : 204);
            }

            return Http::response([], 500);
        });

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->twice()->andReturn($this->sentResult());

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(2);

        self::assertSame(2, $stats['sent']);
        self::assertSame(2, $patchCount);
    }

    public function test_invalid_token_cleanup_failure_is_recorded_as_retryable_cleanup_error(): void
    {
        Http::fake(function ($request) {
            if (str_contains($request->url(), '/rpc/api_claim_fcm_delivery_batch')) {
                return Http::response([$this->attempt()], 200);
            }
            if (str_contains($request->url(), '/rpc/api_unregister_fcm_token_for_delivery')) {
                return Http::response([], 500);
            }
            if (str_contains($request->url(), '/fcm_delivery_attempts')) {
                return Http::response([], 204);
            }

            return Http::response([], 500);
        });

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->andReturn([
            'sent' => false,
            'invalid_token' => true,
            'retryable' => false,
            'status' => 404,
            'retry_after_seconds' => null,
            'provider_message_name' => null,
            'error_code' => 'UNREGISTERED',
        ]);

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['failed']);
        self::assertSame(0, $stats['invalid_tokens']);
        Http::assertSent(fn ($request) => str_contains($request->url(), '/fcm_delivery_attempts')
            && $request->method() === 'PATCH'
            && ($request->data()['status'] ?? null) === 'failed'
            && ($request->data()['last_error_code'] ?? null) === 'TOKEN_CLEANUP_FAILURE');
    }

    public function test_malformed_claimed_attempt_is_skipped_without_sending(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([
                ['attempt_id' => 0, 'notification_id' => 701, 'profile_id' => 91, 'token' => 'device-token'],
            ], 200),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['skipped']);
        self::assertSame(0, $stats['sent']);
    }

    public function test_invalid_token_is_marked_only_after_successful_cleanup(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([$this->attempt()], 200),
            'https://project.supabase.co/rest/v1/rpc/api_unregister_fcm_token_for_delivery' => Http::response([], 204),
            'https://project.supabase.co/rest/v1/fcm_delivery_attempts*' => Http::response([], 204),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->andReturn([
            'sent' => false,
            'invalid_token' => true,
            'retryable' => false,
            'status' => 404,
            'retry_after_seconds' => null,
            'provider_message_name' => null,
            'error_code' => 'UNREGISTERED',
        ]);

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['invalid_tokens']);
        self::assertSame(0, $stats['failed']);
        Http::assertSent(fn ($request) => str_contains($request->url(), '/rpc/api_unregister_fcm_token_for_delivery')
            && $request->method() === 'POST'
            && ($request->data()['p_profile_id'] ?? null) === 91
            && ($request->data()['p_token'] ?? null) === 'device-token-a');
        Http::assertSent(fn ($request) => str_contains($request->url(), '/fcm_delivery_attempts')
            && $request->method() === 'PATCH'
            && ($request->data()['status'] ?? null) === 'invalid_token');
    }

    public function test_retryable_provider_result_is_persisted_as_failed_with_backoff(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([$this->attempt()], 200),
            'https://project.supabase.co/rest/v1/fcm_delivery_attempts*' => Http::response([], 204),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->andReturn([
            'sent' => false,
            'invalid_token' => false,
            'retryable' => true,
            'status' => 503,
            'retry_after_seconds' => 30,
            'provider_message_name' => null,
            'error_code' => 'UNAVAILABLE',
        ]);

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['failed']);
        Http::assertSent(fn ($request) => str_contains($request->url(), '/fcm_delivery_attempts')
            && $request->method() === 'PATCH'
            && ($request->data()['status'] ?? null) === 'failed'
            && ($request->data()['last_error_code'] ?? null) === 'UNAVAILABLE'
            && strtotime((string) ($request->data()['next_attempt_at'] ?? '')) > time());
    }

    public function test_permanent_provider_failure_is_not_treated_as_invalid_token(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([$this->attempt()], 200),
            'https://project.supabase.co/rest/v1/fcm_delivery_attempts*' => Http::response([], 204),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->andReturn([
            'sent' => false,
            'invalid_token' => false,
            'retryable' => false,
            'status' => 400,
            'retry_after_seconds' => null,
            'provider_message_name' => null,
            'error_code' => 'INVALID_ARGUMENT',
        ]);

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['failed']);
        self::assertSame(0, $stats['invalid_tokens']);
        Http::assertNotSent(fn ($request) => str_contains($request->url(), '/rpc/api_unregister_fcm_token_for_delivery'));
        Http::assertSent(fn ($request) => str_contains($request->url(), '/fcm_delivery_attempts')
            && ($request->data()['status'] ?? null) === 'permanent_failure'
            && ($request->data()['last_error_code'] ?? null) === 'INVALID_ARGUMENT');
    }

    public function test_send_exception_is_recorded_and_later_recipient_is_processed(): void
    {
        $patches = [];
        Http::fake(function ($request) use (&$patches) {
            if (str_contains($request->url(), '/rpc/api_claim_fcm_delivery_batch')) {
                return Http::response([$this->attempt(901), $this->attempt(902, 'device-token-b')], 200);
            }
            if (str_contains($request->url(), '/fcm_delivery_attempts')) {
                $patches[] = $request->data();
                return Http::response([], 204);
            }

            return Http::response([], 500);
        });

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->ordered()
            ->andThrow(new \RuntimeException('provider details must not be logged'));
        $fcm->shouldReceive('send')->once()->ordered()->andReturn($this->sentResult());

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(2);

        self::assertSame(1, $stats['failed']);
        self::assertSame(1, $stats['sent']);
        self::assertSame('TRANSPORT_FAILURE', $patches[0]['last_error_code'] ?? null);
        self::assertSame('sent', $patches[1]['status'] ?? null);
    }

    public function test_invalid_token_cleanup_failure_does_not_stop_next_recipient(): void
    {
        $patchedStatuses = [];
        Http::fake(function ($request) use (&$patchedStatuses) {
            if (str_contains($request->url(), '/rpc/api_claim_fcm_delivery_batch')) {
                return Http::response([$this->attempt(911), $this->attempt(912, 'device-token-b')], 200);
            }
            if (str_contains($request->url(), '/rpc/api_unregister_fcm_token_for_delivery')) {
                return Http::response([], 503);
            }
            if (str_contains($request->url(), '/fcm_delivery_attempts')) {
                $patchedStatuses[] = $request->data()['status'] ?? null;
                return Http::response([], 204);
            }

            return Http::response([], 500);
        });

        $invalid = [
            'sent' => false,
            'invalid_token' => true,
            'retryable' => false,
            'status' => 404,
            'retry_after_seconds' => null,
            'provider_message_name' => null,
            'error_code' => 'UNREGISTERED',
        ];
        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->twice()->andReturn($invalid, $this->sentResult());

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(2);

        self::assertSame(1, $stats['failed']);
        self::assertSame(1, $stats['sent']);
        self::assertSame(['failed', 'sent'], $patchedStatuses);
    }

    public function test_missing_fcm_project_id_fails_before_queue_claim(): void
    {
        Http::fake();
        config(['tacru.fcm_project_id' => '']);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldNotReceive('assertCredentialsAvailable');
        $fcm->shouldNotReceive('send');

        try {
            (new FcmNotificationDispatcher($fcm))->dispatch(1);
            self::fail('Expected missing FCM project configuration to fail closed.');
        } catch (\RuntimeException $exception) {
            self::assertSame('FCM dispatcher requires FCM_PROJECT_ID.', $exception->getMessage());
        }

        Http::assertNothingSent();
    }

    public function test_non_array_claim_response_is_rejected_before_send(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response('unexpected', 200),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        try {
            (new FcmNotificationDispatcher($fcm))->dispatch(1);
            self::fail('Expected malformed queue response to abort dispatch.');
        } catch (\RuntimeException $exception) {
            self::assertSame('Supabase FCM delivery queue claim failed.', $exception->getMessage());
        }
    }

    public function test_non_array_claim_row_is_counted_as_skipped_without_blocking_valid_rows(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([
                'malformed-row',
                $this->attempt(802, 'device-token-b'),
            ], 200),
            'https://project.supabase.co/rest/v1/fcm_delivery_attempts*' => Http::response([], 204),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->with('tacresq-test', 'device-token-b', [
            'notification_id' => '701',
            'recipient_profile_id' => '91',
            'event_type' => 'incident_status_changed',
            'incident_id' => '44',
        ])->andReturn($this->sentResult());

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(2);

        self::assertSame(2, $stats['tokens']);
        self::assertSame(1, $stats['skipped']);
        self::assertSame(1, $stats['sent']);
    }

    public function test_object_claim_payload_is_rejected_as_not_a_delivery_list(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([
                'attempt_id' => 801,
                'notification_id' => 701,
                'profile_id' => 91,
                'token' => 'device-token-a',
            ], 200),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        try {
            (new FcmNotificationDispatcher($fcm))->dispatch(1);
            self::fail('Expected an object-shaped claim payload to be rejected.');
        } catch (\RuntimeException $exception) {
            self::assertSame('Supabase FCM delivery queue claim failed.', $exception->getMessage());
        }
    }

    public function test_empty_queue_returns_zero_counts_without_sending(): void
    {
        Http::fake([
            'https://project.supabase.co/rest/v1/rpc/api_claim_fcm_delivery_batch' => Http::response([], 200),
        ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame([
            'notifications' => 0,
            'tokens' => 0,
            'sent' => 0,
            'invalid_tokens' => 0,
            'failed' => 0,
            'skipped' => 0,
        ], $stats);
    }

    public function test_cleanup_failure_log_contains_only_attempt_and_exception_class(): void
    {
        Http::fake(function ($request) {
            if (str_contains($request->url(), '/rpc/api_claim_fcm_delivery_batch')) {
                return Http::response([$this->attempt()], 200);
            }
            if (str_contains($request->url(), '/rpc/api_unregister_fcm_token_for_delivery')) {
                return Http::response(['message' => 'private token-bearing provider detail'], 500);
            }

            return Http::response([], 204);
        });

        Log::shouldReceive('warning')->once()
            ->with('FCM invalid-token cleanup failed for attempt.', [
                'attempt_id' => 801,
                'exception' => \RuntimeException::class,
            ]);

        $fcm = Mockery::mock(FcmClientContract::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldReceive('send')->once()->andReturn([
            'sent' => false,
            'invalid_token' => true,
            'retryable' => false,
            'status' => 404,
            'retry_after_seconds' => null,
            'provider_message_name' => null,
            'error_code' => 'UNREGISTERED',
        ]);

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['failed']);
        self::assertSame(0, $stats['invalid_tokens']);
    }

    private function attempt(int $attemptId = 801, string $token = 'device-token-a'): array
    {
        return [
            'attempt_id' => $attemptId,
            'notification_id' => 701,
            'profile_id' => 91,
            'token' => $token,
            'attempt_count' => 1,
            'event_type' => 'incident_status_changed',
            'incident_id' => 44,
        ];
    }

    private function sentResult(): array
    {
        return [
            'sent' => true,
            'invalid_token' => false,
            'retryable' => false,
            'status' => 200,
            'retry_after_seconds' => null,
            'provider_message_name' => 'projects/tacresq-test/messages/abc123',
            'error_code' => null,
        ];
    }
}
