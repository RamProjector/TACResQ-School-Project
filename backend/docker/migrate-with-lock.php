<?php

declare(strict_types=1);

require '/app/vendor/autoload.php';

$app = require '/app/bootstrap/app.php';
$kernel = $app->make(Illuminate\\Contracts\\Console\\Kernel::class);
$kernel->bootstrap();

$connection = Illuminate\\Support\\Facades\\DB::connection();
$lockId = 741852963014159265;
$locked = false;
$exitCode = 1;

try {
    // Render can briefly run old and new instances together during deployment.
    // Hold a PostgreSQL session advisory lock across the entire migration command
    // so only one instance can apply pending schema changes at a time.
    if ($connection->getDriverName() === 'pgsql') {
        $connection->select('SELECT pg_advisory_lock(?)', [$lockId]);
        $locked = true;
    }

    $exitCode = Illuminate\\Support\\Facades\\Artisan::call('migrate', ['--force' => true]);
    fwrite(STDOUT, Illuminate\\Support\\Facades\\Artisan::output());
} catch (Throwable $exception) {
    fwrite(STDERR, 'TACResQ startup migration failed: '.$exception->getMessage().PHP_EOL);
    $exitCode = 1;
} finally {
    if ($locked) {
        try {
            $connection->select('SELECT pg_advisory_unlock(?)', [$lockId]);
        } catch (Throwable $exception) {
            fwrite(STDERR, 'TACResQ could not release the migration lock cleanly.'.PHP_EOL);
            $exitCode = 1;
        }
    }
}

exit($exitCode);
