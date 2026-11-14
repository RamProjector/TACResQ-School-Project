<?php

namespace App\Console\Commands;

use App\FcmNotificationDispatcher;
use Illuminate\Console\Command;

final class DispatchFcmNotifications extends Command
{
    protected $signature = 'tacresq:dispatch-fcm {--limit= : Maximum due notification/token deliveries to claim} {--check-config : Validate Supabase/Firebase configuration without sending}';
    protected $description = 'Deliver due TACResQ notification attempts through Firebase Cloud Messaging';

    public function handle(FcmNotificationDispatcher $dispatcher): int
    {
        try {
            if ((bool) $this->option('check-config')) {
                $dispatcher->checkConfiguration();
                $this->info('FCM configuration and ADC credentials are available; no notification was sent.');
                return self::SUCCESS;
            }
            $limit = $this->option('limit');
            $limit = $limit === null ? null : (int) $limit;
            $stats = $dispatcher->dispatch($limit);
        } catch (\Throwable $exception) {
            // Never print configuration values, provider response bodies, or tokens.
            $this->error($exception->getMessage());
            return self::FAILURE;
        }

        $this->line(sprintf(
            'FCM dispatch complete: notifications=%d tokens=%d sent=%d invalid_tokens=%d failed=%d skipped=%d',
            $stats['notifications'],
            $stats['tokens'],
            $stats['sent'],
            $stats['invalid_tokens'],
            $stats['failed'],
            $stats['skipped'],
        ));
        return $stats['failed'] > 0 ? self::FAILURE : self::SUCCESS;
    }
}
