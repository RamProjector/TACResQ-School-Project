<?php

namespace Tests\Feature;

use App\FcmHttpV1Client;
use App\FcmNotificationDispatcher;
use Illuminate\Support\Facades\Http;
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

    public function test_invalid_explicit_limit_is_rejected_before_queue_claim(): void
    {
        Http::fake();

        $fcm = Mockery::mock(FcmHttpV1Client::class);
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

        $fcm = Mockery::mock(FcmHttpV1Client::class);
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

        $fcm = Mockery::mock(FcmHttpV1Client::class);
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
            if (str_contains($request->url(), '/fcm_tokens')) {
                return Http::response([], 500);
            }
            if (str_contains($request->url(), '/fcm_delivery_attempts')) {
                return Http::response([], 204);
            }

            return Http::response([], 500);
        });

        $fcm = Mockery::mock(FcmHttpV1Client::class);
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

        $fcm = Mockery::mock(FcmHttpV1Client::class);
        $fcm->shouldReceive('assertCredentialsAvailable')->once();
        $fcm->shouldNotReceive('send');

        $stats = (new FcmNotificationDispatcher($fcm))->dispatch(1);

        self::assertSame(1, $stats['skipped']);
        self::assertSame(0, $stats['sent']);
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
