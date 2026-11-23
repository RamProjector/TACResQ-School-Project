<?php

namespace Tests\Feature;

use App\FcmNotificationDispatcher;
use Mockery;
use RuntimeException;
use Tests\TestCase;

final class DispatchFcmCommandTest extends TestCase
{
    public function test_dispatch_failure_does_not_echo_sensitive_exception_details(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldReceive('dispatch')->once()->with(null)
            ->andThrow(new RuntimeException('synthetic secret-bearing provider response'));
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $this->artisan('tacresq:dispatch-fcm')
            ->expectsOutput('FCM dispatch failed. Check server logs for the diagnostic.')
            ->doesntExpectOutput('synthetic secret-bearing provider response')
            ->assertExitCode(1);
    }
}
