<?php

namespace Tests\Feature;

use App\FcmNotificationDispatcher;
use Illuminate\Support\Facades\Cache;
use Mockery;
use RuntimeException;
use Tests\TestCase;

final class FcmDispatchWebhookTest extends TestCase
{
    private const SECRET = 'synthetic-fcm-dispatch-hmac-secret-with-more-than-32-bytes';

    protected function setUp(): void
    {
        parent::setUp();
        Cache::flush();
        config(['tacru.fcm_dispatch_webhook_secret' => self::SECRET]);
    }

    public function test_valid_signed_request_dispatches_only_a_fixed_small_batch(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldReceive('dispatch')->once()->with(5)->andReturn($this->stats());
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $this->signedRequest()
            ->assertOk()
            ->assertHeader('Cache-Control', 'no-store, private')
            ->assertJsonPath('ok', true)
            ->assertJsonPath('counts.sent', 3);
    }

    public function test_missing_or_short_secret_fails_closed_without_dispatching(): void
    {
        config(['tacru.fcm_dispatch_webhook_secret' => 'too-short']);
        $this->signedRequest()->assertServiceUnavailable();
    }

    public function test_missing_signature_headers_are_rejected(): void
    {
        $this->call('POST', '/internal/fcm/dispatch', [], [], [], ['CONTENT_TYPE' => 'application/json'], '{}')
            ->assertUnauthorized();
    }

    public function test_invalid_signature_is_rejected_without_dispatching(): void
    {
        $this->signedRequest(signature: str_repeat('a', 64))->assertUnauthorized();
    }

    public function test_expired_timestamp_is_rejected_even_with_a_valid_signature(): void
    {
        $this->signedRequest(timestamp: (string) (time() - 301))->assertUnauthorized();
    }

    public function test_nonempty_payload_is_rejected_even_when_signed(): void
    {
        $this->signedRequest(body: '{"limit":50}')->assertStatus(422);
    }

    public function test_query_parameters_are_not_accepted(): void
    {
        $this->signedRequest(uri: '/internal/fcm/dispatch?limit=50')->assertStatus(422);
    }

    public function test_request_id_cannot_be_replayed(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldReceive('dispatch')->once()->with(5)->andReturn($this->stats());
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);
        $requestId = 'd9b1f9e8-4b91-4c36-9f2e-7b4b1a6e5d22';

        $this->signedRequest(requestId: $requestId)->assertOk();
        $this->signedRequest(requestId: $requestId)->assertStatus(409);
    }

    public function test_overlapping_dispatch_is_skipped_by_the_single_instance_lock(): void
    {
        $lock = Cache::lock('tacresq:fcm-dispatch:worker', 90);
        self::assertTrue($lock->get());

        try {
            $this->signedRequest()
                ->assertStatus(202)
                ->assertJsonPath('skipped', 'dispatch_already_running');
        } finally {
            $lock->release();
        }
    }

    public function test_dispatch_exception_is_sanitized(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldReceive('dispatch')->once()->with(5)
            ->andThrow(new RuntimeException('synthetic secret-bearing diagnostic'));
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $response = $this->signedRequest()->assertServiceUnavailable();
        self::assertStringNotContainsString('synthetic secret-bearing diagnostic', $response->getContent());
    }

    private function signedRequest(
        ?string $timestamp = null,
        ?string $requestId = null,
        string $body = '{}',
        ?string $signature = null,
        string $uri = '/internal/fcm/dispatch',
    ) {
        $timestamp ??= (string) time();
        $requestId ??= 'd9b1f9e8-4b91-4c36-9f2e-7b4b1a6e5d23';
        $signature ??= hash_hmac('sha256', $timestamp."\n".$requestId."\n".$body, self::SECRET);

        return $this->call('POST', $uri, [], [], [], [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_X_TACRESQ_TIMESTAMP' => $timestamp,
            'HTTP_X_TACRESQ_REQUEST_ID' => $requestId,
            'HTTP_X_TACRESQ_SIGNATURE' => $signature,
        ], $body);
    }

    private function stats(): array
    {
        return [
            'notifications' => 2,
            'tokens' => 3,
            'sent' => 3,
            'invalid_tokens' => 0,
            'failed' => 0,
            'skipped' => 0,
        ];
    }
}
