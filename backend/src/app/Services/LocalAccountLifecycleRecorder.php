<?php

namespace App\Services;

use Illuminate\Database\ConnectionResolverInterface;
use InvalidArgumentException;
use LogicException;

final class LocalAccountLifecycleRecorder
{
    public function __construct(
        private readonly ConnectionResolverInterface $database,
    ) {
    }

    private const TRANSITIONS = [
        'suspended' => ['active', 'suspended'],
        'reactivated' => ['suspended', 'active'],
    ];

    /**
     * Append a lifecycle event inside the caller's account-state transaction.
     *
     * The recorder deliberately does not change account state or authorize an
     * actor. Callers must perform authorization, lock the target row, update
     * users, and invoke this method in the same transaction.
     */
    public function record(
        int $actorUserId,
        int $targetUserId,
        string $action,
        string $reason,
        string $previousState,
        string $newState,
    ): int {
        if ($this->database->connection()->transactionLevel() < 1) {
            throw new LogicException('Account lifecycle events must be recorded inside the account-state transaction.');
        }

        if ($actorUserId < 1 || $targetUserId < 1 || $actorUserId === $targetUserId) {
            throw new InvalidArgumentException('A valid, distinct actor and target are required.');
        }

        $reason = trim($reason);
        if ($reason === '' || mb_strlen($reason) > 500) {
            throw new InvalidArgumentException('A lifecycle reason between 1 and 500 characters is required.');
        }

        $expectedStates = self::TRANSITIONS[$action] ?? null;
        if ($expectedStates === null
            || $expectedStates[0] !== $previousState
            || $expectedStates[1] !== $newState) {
            throw new InvalidArgumentException('The lifecycle action and state transition are not supported.');
        }

        return (int) $this->database->table('local_account_lifecycle_events')->insertGetId([
            'actor_user_id' => $actorUserId,
            'target_user_id' => $targetUserId,
            'action' => $action,
            'reason' => $reason,
            'previous_state' => $previousState,
            'new_state' => $newState,
            'created_at' => now(),
        ]);
    }
}
