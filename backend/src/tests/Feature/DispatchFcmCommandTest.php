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
    public function test_zero_batch_limit_is_rejected_without_dispatching(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldNotReceive('dispatch');
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $this->artisan('tacresq:dispatch-fcm', ['--limit' => '0'])
            ->expectsOutput('The --limit option must be an integer between 1 and 50.')
            ->assertExitCode(2);
    }

    public function test_batch_limit_above_database_cap_is_rejected(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldNotReceive('dispatch');
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $this->artisan('tacresq:dispatch-fcm', ['--limit' => '51'])
            ->expectsOutput('The --limit option must be an integer between 1 and 50.')
            ->assertExitCode(2);
    }

    public function test_non_integer_batch_limit_is_rejected(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldNotReceive('dispatch');
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $this->artisan('tacresq:dispatch-fcm', ['--limit' => '3abc'])
            ->expectsOutput('The --limit option must be an integer between 1 and 50.')
            ->assertExitCode(2);
    }

    public function test_check_config_validates_credentials_without_dispatching(): void
    {
        $dispatcher = Mockery::mock(FcmNotificationDispatcher::class);
        $dispatcher->shouldReceive('checkConfiguration')->once();
        $dispatcher->shouldNotReceive('dispatch');
        $this->app->instance(FcmNotificationDispatcher::class, $dispatcher);

        $this->artisan('tacresq:dispatch-fcm', ['--check-config' => true])
            ->expectsOutput('FCM configuration and ADC credentials are available; no notification was sent.')
            ->assertExitCode(0);
    }

}
