<?php

namespace Tests\Feature;

use App\TraceContext;
use Tests\TestCase;

class TraceContextTest extends TestCase
{
    public function test_accepts_and_normalizes_a_valid_version_zero_traceparent(): void
    {
        request()->headers->set(
            'traceparent',
            '00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01',
        );

        $this->assertSame(
            '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
            TraceContext::currentTraceparent(),
        );
    }

    public function test_rejects_invalid_trace_context_without_throwing(): void
    {
        foreach (self::invalidTraceparents() as $label => $value) {
            request()->headers->set('traceparent', $value);
            $this->assertNull(
                TraceContext::currentTraceparent(),
                "Invalid traceparent case should be rejected: {$label}",
            );
        }
    }

    private static function invalidTraceparents(): array
    {
        return [
            'missing header' => '',
            'unsupported version' => '01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
            'zero trace ID' => '00-00000000000000000000000000000000-00f067aa0ba902b7-01',
            'zero span ID' => '00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01',
            'truncated value' => '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7',
            'extra suffix' => '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra',
        ];
    }
}
