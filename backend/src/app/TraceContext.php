<?php

namespace App;

/**
 * Validate caller-provided W3C trace context before forwarding it upstream.
 * A trace ID is correlation metadata only; it is never identity or authority.
 */
final class TraceContext
{
    public static function currentTraceparent(): ?string
    {
        $value = request()->header('traceparent');
        if (!is_string($value) || strlen($value) !== 55
            || preg_match('/\\A00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})\\z/iD', $value, $matches) !== 1
            || $matches[1] === str_repeat('0', 32)
            || $matches[2] === str_repeat('0', 16)) {
            return null;
        }

        return strtolower($value);
    }
}
