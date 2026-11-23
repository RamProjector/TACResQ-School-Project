<?php

use App\Models\Incident;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\Facades\Route;
use Illuminate\Validation\Rule;

$userJson = static fn (User $user) => [
    'id' => $user->id, 'name' => $user->name, 'email' => $user->email,
    'phone' => $user->phone, 'role' => $user->role, 'duty_status' => $user->duty_status,
];
$incidentJson = static function (Incident $incident): array {
    $incident->loadMissing('requester:id,name,phone');
    return [
        'id' => $incident->id, 'type' => $incident->type,
        'description' => $incident->description,
        'latitude' => $incident->latitude === null ? null : (float) $incident->latitude,
        'longitude' => $incident->longitude === null ? null : (float) $incident->longitude,
        'accuracy_m' => $incident->accuracy_m === null ? null : (float) $incident->accuracy_m,
        'location_notes' => $incident->location_notes,
        'location_recorded_at' => $incident->location_recorded_at?->toISOString(),
        'location_source' => $incident->location_source,
        'status' => $incident->status, 'requester_name' => $incident->requester?->name,
        'requester_phone' => $incident->requester?->phone, 'assigned_to' => $incident->assigned_to,
        'created_at' => $incident->created_at?->toISOString(),
    ];
};
$onlyRoles = static function (Request $request, array $roles): void {
    abort_unless(in_array($request->user()->role, $roles, true), 403, 'You are not allowed to perform this action.');
};

Route::post('/login', function (Request $request) use ($userJson) {
    $credentials = $request->validate([
        'email' => ['required', 'string', 'email', 'max:255'],
        // Match the legacy registration and password-change input ceiling so
        // oversized credential strings are rejected before hashing work.
        'password' => ['required', 'string', 'max:72'],
    ]);
    $normalizedEmail = mb_strtolower(trim($credentials['email']));
    // Combine account and source to slow targeted guessing without allowing one
    // hostile network to lock the account for every legitimate user.
    $accountKey = 'auth-login-account:'.hash('sha256', $normalizedEmail.'|'.(string) $request->ip());
    $ipKey = 'auth-login-ip:'.hash('sha256', (string) $request->ip());

    // A generous source ceiling complements the stricter per-account/source
    // bucket and avoids overly aggressive limits on carrier-grade NAT networks.
    if (RateLimiter::tooManyAttempts($ipKey, 120)) {
        return response()->json(['message' => 'Too many sign-in attempts. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($ipKey))
            ->header('Cache-Control', 'no-store, private');
    }
    RateLimiter::hit($ipKey, 60);

    if (RateLimiter::tooManyAttempts($accountKey, 5)) {
        return response()->json(['message' => 'Too many sign-in attempts. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($accountKey))
            ->header('Cache-Control', 'no-store, private');
    }

    $user = User::whereRaw('LOWER(email) = ?', [$normalizedEmail])->first();
    if (!$user || !Hash::check($credentials['password'], $user->password)) {
        RateLimiter::hit($accountKey, 900);
        return response()->json(['message' => 'The provided credentials are incorrect.'], 422)
            ->header('Cache-Control', 'no-store, private');
    }

    RateLimiter::clear($accountKey);
    if ($user->is_suspended) {
        return response()->json([
            'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
        ], 403)->header('Cache-Control', 'no-store, private');
    }
    return response()->json(['token' => $user->createToken('android')->plainTextToken, 'user' => $userJson($user)])
        ->header('Cache-Control', 'no-store, private');
});

Route::post('/register', function (Request $request) use ($userJson) {
    // Registration is public by design, but account creation must be bounded
    // before validation/database work to reduce automated signup abuse.
    $registrationKey = 'auth-register-ip:'.hash('sha256', (string) $request->ip());
    if (RateLimiter::tooManyAttempts($registrationKey, 10)) {
        return response()->json(['message' => 'Too many registration attempts. Please retry later.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($registrationKey))
            ->header('Cache-Control', 'no-store, private');
    }
    RateLimiter::hit($registrationKey, 900);

    $data = $request->validate([
        'name' => ['required', 'string', 'max:120'], 'email' => ['required', 'email', 'max:255'],
        'phone' => ['nullable', 'string', 'max:40'], 'password' => ['required', 'string', 'min:8', 'max:72'],
    ]);
    $data['name'] = trim($data['name']);
    $data['email'] = mb_strtolower(trim($data['email']));

    // Normalize before lookup so case variants cannot create separate accounts.
    $existing = User::whereRaw('LOWER(email) = ?', [$data['email']])->exists();
    abort_if($existing, 422, 'Unable to create an account with these details.');

    $user = User::create([...$data, 'password' => Hash::make($data['password']), 'role' => 'requester', 'duty_status' => null]);
    return response()->json(['token' => $user->createToken('android')->plainTextToken, 'user' => $userJson($user)], 201)
        ->header('Cache-Control', 'no-store, private');
});

Route::middleware(['auth:sanctum', 'account.active'])->group(function () use ($userJson, $incidentJson, $onlyRoles) {
    Route::post('/logout', fn (Request $request) => tap(
        response()->noContent()->header('Cache-Control', 'no-store, private'),
        fn () => $request->user()->currentAccessToken()?->delete()
    ))->withoutMiddleware('account.active');
    // Self-service recovery control: invalidate every Sanctum personal access
    // token for this account, including the token used for this request.
    Route::post('/logout-all', function (Request $request) {
        $request->user()->tokens()->delete();
        return response()->json(['message' => 'All Laravel API tokens have been revoked.'], 200)
            ->header('Cache-Control', 'no-store, private');
    })->middleware('throttle:10,1')->withoutMiddleware('account.active');
    Route::post('/change-password', function (Request $request) use ($userJson) {
        $data = $request->validate([
            'current_password' => ['required', 'string', 'max:72'],
            'password' => ['required', 'string', 'min:12', 'max:72', 'confirmed'],
        ]);
        $user = $request->user();
        $attemptKey = 'auth-password-change:'.hash('sha256', (string) $user->getAuthIdentifier());

        if (RateLimiter::tooManyAttempts($attemptKey, 5)) {
            return response()->json(['message' => 'Too many password-change attempts. Please retry later.'], 429)
                ->header('Retry-After', (string) RateLimiter::availableIn($attemptKey))
                ->header('Cache-Control', 'no-store, private');
        }

        if (!Hash::check($data['current_password'], $user->password)) {
            RateLimiter::hit($attemptKey, 900);
            return response()->json(['message' => 'The current password is incorrect.'], 422)
                ->header('Cache-Control', 'no-store, private');
        }

        if (Hash::check($data['password'], $user->password)) {
            return response()->json(['message' => 'Choose a password different from your current password.'], 422)
                ->header('Cache-Control', 'no-store, private');
        }

        $user->forceFill(['password' => Hash::make($data['password'])])->save();

        // Rotate every Sanctum session after a credential change, then return
        // one fresh token so the account owner is not stranded mid-recovery.
        $user->tokens()->delete();
        $token = $user->createToken('android')->plainTextToken;
        RateLimiter::clear($attemptKey);

        return response()->json(['token' => $token, 'user' => $userJson($user->fresh())], 200)
            ->header('Cache-Control', 'no-store, private');
    })->middleware('throttle:5,1');
    Route::get('/me', fn (Request $request) => response()->json($userJson($request->user())));
    Route::patch('/me/duty-status', function (Request $request) use ($userJson, $onlyRoles) {
        $onlyRoles($request, ['responder', 'driver']);
        $data = $request->validate(['duty_status' => ['required', Rule::in(['available', 'off_duty'])]]);
        $updated = DB::transaction(function () use ($request, $data) {
            // Serialize duty changes with dispatcher assignment, which locks this
            // same user row before checking availability and creating membership.
            $unit = User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();
            $active = Incident::where(function ($query) use ($unit) {
                $query->where('assigned_to', $unit->id)
                    ->orWhereExists(function ($assignment) use ($unit) {
                        $assignment->selectRaw('1')
                            ->from('incident_assignments')
                            ->whereColumn('incident_assignments.incident_id', 'incidents.id')
                            ->where('incident_assignments.user_id', $unit->id);
                    });
            })->whereIn('status', ['assigned', 'en_route', 'on_scene', 'transporting'])->exists();
            abort_if($active, 422, 'Duty status cannot be changed while an incident is active.');
            $unit->update($data);
            return $unit->fresh();
        });
        return response()->json($userJson($updated));
    })->middleware('throttle:30,1');
    Route::get('/incidents/mine', function (Request $request) use ($incidentJson) {
        // Preserve the legacy JSON-array response while bounding list work.
        $validated = $request->validate(['limit' => ['sometimes', 'integer', 'min:1', 'max:100']]);
        $limit = (int) ($validated['limit'] ?? 100);
        $user = $request->user();
        $query = Incident::query();
        if ($user->role === 'requester') $query->where('requester_id', $user->id);
        elseif (in_array($user->role, ['responder', 'driver'], true)) {
            // Include every response-team member while retaining the legacy lead assignment.
            $query->where(function ($membership) use ($user) {
                $membership->where('assigned_to', $user->id)
                    ->orWhereExists(function ($assignment) use ($user) {
                        $assignment->selectRaw('1')
                            ->from('incident_assignments')
                            ->whereColumn('incident_assignments.incident_id', 'incidents.id')
                            ->where('incident_assignments.user_id', $user->id);
                    });
            });
            // Distinguish the legacy lead from other team members without N+1 queries.
            $query->select('incidents.*')->selectRaw(
                "CASE WHEN incidents.assigned_to = ? THEN 'lead' ELSE 'team' END AS assignment_role",
                [$user->id]
            );
        }
        else abort(403, 'This account has no personal incident list.');
        return response()->json($query->orderByDesc('created_at')->orderByDesc('id')->limit($limit)->get()->map(
            static function (Incident $incident) use ($incidentJson, $user): array {
                $payload = $incidentJson($incident);
                if (in_array($user->role, ['responder', 'driver'], true)) {
                    $payload['assignment_role'] = $incident->assignment_role;
                }
                return $payload;
            }
        )->values());
    })->middleware('throttle:120,1');
    Route::post('/incidents', function (Request $request) use ($incidentJson, $onlyRoles) {
        $onlyRoles($request, ['requester']);
        $data = $request->validate([
            'client_request_id' => ['required', 'uuid'],
            'type' => ['required', Rule::in(['medical', 'fire', 'vehicular', 'disaster', 'other'])],
            'description' => ['nullable', 'string', 'max:2000'],
            'latitude' => ['nullable', 'numeric', 'between:-90,90', 'required_with:longitude'],
            'longitude' => ['nullable', 'numeric', 'between:-180,180', 'required_with:latitude'],
            'accuracy_m' => ['nullable', 'numeric', 'min:0', 'max:100000'],
            'location_notes' => ['nullable', 'string', 'max:500'],
            'location_recorded_at' => ['nullable', 'date'],
            'location_source' => ['nullable', Rule::in(['gps', 'manual', 'none'])],
        ]);
        abort_if(
            isset($data['latitude']) !== isset($data['longitude']),
            422,
            'Latitude and longitude must be supplied together.'
        );
        $hasCoordinates = isset($data['latitude']) && isset($data['longitude']);
        $data['location_source'] ??= $hasCoordinates ? 'gps' : 'none';
        abort_if(
            !$hasCoordinates && $data['location_source'] !== 'none',
            422,
            'A missing location must use source none.'
        );
        abort_if(
            $hasCoordinates && $data['location_source'] === 'none',
            422,
            'Coordinates require a GPS or manual location source.'
        );
        abort_if(
            $data['location_source'] === 'manual' && ($data['accuracy_m'] ?? null) !== null,
            422,
            'A manually selected pin cannot claim GPS accuracy.'
        );
        abort_if(
            !$hasCoordinates && (($data['accuracy_m'] ?? null) !== null || ($data['location_recorded_at'] ?? null) !== null),
            422,
            'Accuracy and capture time require a coordinate pair.'
        );
        abort_if(
            $hasCoordinates && ($data['location_recorded_at'] ?? null) === null,
            422,
            'A location capture time is required with coordinates.'
        );
        $data['location_notes'] = isset($data['location_notes']) ? (trim($data['location_notes']) ?: null) : null;

        $requestId = $data['client_request_id'];
        unset($data['client_request_id']);
        $requestPayload = json_encode(
            $data,
            JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE
        );

        $result = DB::transaction(function () use ($data, $request, $requestId, $requestPayload, $incidentJson) {
            // Lock the account row so simultaneous submissions from this requester
            // cannot both pass the active-request check.
            User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();

            $existingKey = DB::table('incident_submission_keys')
                ->where('requester_id', $request->user()->id)
                ->where('client_request_id', $requestId)
                ->first();

            if ($existingKey) {
                if (!hash_equals((string) $existingKey->request_payload, $requestPayload)) {
                    abort(409, 'This request ID was already used for different request details.');
                }
                if ($existingKey->response_payload !== null) {
                    return [
                        'payload' => json_decode($existingKey->response_payload, true, 512, JSON_THROW_ON_ERROR),
                        'replayed' => true,
                    ];
                }
                abort(409, 'This request is still being processed. Please retry shortly.');
            }

            $active = Incident::where('requester_id', $request->user()->id)
                ->whereIn('status', ['pending', 'assigned', 'en_route', 'on_scene', 'transporting'])
                ->exists();
            abort_if($active, 409, 'You already have an active request. Cancel or resolve it before submitting another.');

            DB::table('incident_submission_keys')->insert([
                'requester_id' => $request->user()->id,
                'client_request_id' => $requestId,
                'request_payload' => $requestPayload,
                'response_payload' => null,
                'created_at' => now(),
            ]);

            $incident = Incident::create([
                ...$data,
                'requester_id' => $request->user()->id,
                'status' => 'pending',
            ]);
            $incident->statusLogs()->create([
                'status' => 'pending',
                'changed_by' => $request->user()->id,
            ]);
            $payload = $incidentJson($incident);

            DB::table('incident_submission_keys')
                ->where('requester_id', $request->user()->id)
                ->where('client_request_id', $requestId)
                ->update(['response_payload' => json_encode($payload, JSON_THROW_ON_ERROR)]);

            return ['payload' => $payload, 'replayed' => false];
        });

        return response()->json($result['payload'], $result['replayed'] ? 200 : 201);
    });
    Route::patch('/incidents/{incident}/status', function (Request $request, Incident $incident) use ($incidentJson, $onlyRoles) {
        // Incident progress is dispatcher-owned. Field units keep using the
        // organization's existing communication procedures and do not duplicate
        // per-person status taps in TACResQ.
        $onlyRoles($request, ['dispatcher']);
        $data = $request->validate(['status' => ['required', Rule::in(['en_route', 'on_scene', 'transporting', 'resolved'])]]);
        $transitions = [
            'assigned' => ['en_route'],
            'en_route' => ['on_scene'],
            'on_scene' => ['transporting', 'resolved'],
            'transporting' => ['resolved'],
        ];

        $updated = DB::transaction(function () use ($incident, $data, $request, $transitions) {
            $locked = Incident::whereKey($incident->id)->lockForUpdate()->firstOrFail();
            abort_if($locked->assigned_to === null, 422, 'Assign a response unit before advancing incident progress.');
            $lead = User::whereKey($locked->assigned_to)->first();
            abort_unless(
                $lead && in_array($lead->role, ['responder', 'driver'], true),
                422,
                'Assigned lead must be a responder or driver.'
            );
            abort_unless(
                in_array($data['status'], $transitions[$locked->status] ?? [], true),
                422,
                'Incident was changed; refresh and retry.'
            );

            $locked->update(['status' => $data['status']]);
            $locked->statusLogs()->create(['status' => $data['status'], 'changed_by' => $request->user()->id]);

            DB::table('incident_assignments')
                ->where('incident_id', $locked->id)
                ->update([
                    'responder_status' => $data['status'],
                    'status_updated_at' => now(),
                ]);

            $assignedUserIds = DB::table('incident_assignments')
                ->where('incident_id', $locked->id)
                ->pluck('user_id')
                ->push($locked->assigned_to)
                ->filter()
                ->unique()
                ->values();
            $units = User::whereIn('id', $assignedUserIds)
                ->orderBy('id')
                ->lockForUpdate()
                ->get();
            abort_unless(
                $units->count() === $assignedUserIds->count()
                    && $units->every(static fn (User $unit): bool => in_array($unit->role, ['responder', 'driver'], true)),
                422,
                'Every assigned response team member must be a responder or driver.'
            );
            $units->each(static fn (User $unit) => $unit->update([
                'duty_status' => $data['status'] === 'resolved' ? 'available' : $data['status'],
            ]));
            return $locked->fresh();
        });

        return response()->json($incidentJson($updated));
    })->middleware('throttle:30,1');
    Route::get('/incidents', function (Request $request) use ($incidentJson, $onlyRoles) {
        $onlyRoles($request, ['dispatcher']);
        $validated = $request->validate(['limit' => ['sometimes', 'integer', 'min:1', 'max:100']]);
        $limit = (int) ($validated['limit'] ?? 100);
        return response()->json(Incident::orderByDesc('created_at')->orderByDesc('id')->limit($limit)->get()->map($incidentJson)->values());
    })->middleware('throttle:120,1');
    Route::get('/units', function (Request $request) use ($userJson, $onlyRoles) {
        $onlyRoles($request, ['dispatcher']);
        $validated = $request->validate(['limit' => ['sometimes', 'integer', 'min:1', 'max:100']]);
        $limit = (int) ($validated['limit'] ?? 100);
        return response()->json(User::whereIn('role', ['responder', 'driver'])->orderBy('name')->orderBy('id')->limit($limit)->get()->map($userJson)->values());
    })->middleware('throttle:120,1');
    Route::post('/incidents/{incident}/assign', function (Request $request, Incident $incident) use ($incidentJson, $onlyRoles) {
        $onlyRoles($request, ['dispatcher']);
        $data = $request->validate(['user_id' => ['required', 'integer', 'exists:users,id']]);
        $updated = DB::transaction(function () use ($incident, $data, $request) {
            $lockedIncident = Incident::whereKey($incident->id)->lockForUpdate()->firstOrFail();
            abort_unless($lockedIncident->status === 'pending', 422, 'Only pending incidents can be assigned.');
            $unit = User::whereKey($data['user_id'])->lockForUpdate()->firstOrFail();
            abort_unless(in_array($unit->role, ['responder', 'driver'], true), 422, 'Selected user is not a field unit.');
            abort_unless($unit->duty_status === 'available', 422, 'Selected unit is not available.');
            $busy = Incident::where(function ($query) use ($unit) {
                $query->where('assigned_to', $unit->id)
                    ->orWhereExists(function ($assignment) use ($unit) {
                        $assignment->selectRaw('1')
                            ->from('incident_assignments')
                            ->whereColumn('incident_assignments.incident_id', 'incidents.id')
                            ->where('incident_assignments.user_id', $unit->id);
                    });
            })->whereIn('status', ['assigned', 'en_route', 'on_scene', 'transporting'])->exists();
            abort_if($busy, 422, 'Selected unit already has an active incident.');
            $lockedIncident->update(['assigned_to' => $unit->id, 'status' => 'assigned']);
            DB::table('incident_assignments')->insert([
                'incident_id' => $lockedIncident->id,
                'user_id' => $unit->id,
                'assigned_by' => $request->user()->id,
                'assigned_at' => now(),
                'responder_status' => 'assigned',
                'status_updated_at' => now(),
            ]);
            $lockedIncident->statusLogs()->create(['status' => 'assigned', 'changed_by' => $request->user()->id]);
            // Keep the unit out of the available pool immediately after assignment.
            // Subsequent dispatcher progress mirrors en_route/on_scene/transporting.
            $unit->update(['duty_status' => 'assigned']);
            return $lockedIncident->fresh();
        });
        return response()->json($incidentJson($updated));
    })->middleware('throttle:30,1');
});

// Transitional Render -> Supabase bridge. Apply a coarse per-IP ceiling before
// validating Supabase tokens so unauthenticated traffic cannot hammer Auth.
// The Render container sets CACHE_STORE=file so throttling stays independent of SQL cache tables.
// Use a higher IP ceiling to avoid locking out users behind carrier NAT. A lower
// per-authenticated-user limit is enforced after the Supabase token is validated.
Route::middleware('throttle:600,1')->group(function () {

// Transitional Render -> Supabase bridge. This is additive; existing clients are
// not switched to it until endpoint parity and end-to-end tests are complete.
Route::get('/bridge/me', function (Request $request, \App\SupabaseBridge $bridge) {
    $token = $request->bearerToken();
    abort_if (!$token, 401, 'A valid Supabase access token is required.');

    try {
        $user = $bridge->getUser($token);
    } catch (\RuntimeException) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }
    abort_if (!$user, 401, 'The Supabase session is invalid or expired.');

    $userRateKey = 'bridge-user:'.hash('sha256', (string) $user['id']);
    if (RateLimiter::tooManyAttempts($userRateKey, 120)) {
        return response()->json(['message' => 'Too many requests. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($userRateKey));
    }
    RateLimiter::hit($userRateKey, 60);

    // Supabase Auth validity alone is not application authorization. Resolve
    // the caller's profile through the user's JWT so active-account RLS is
    // evaluated by Postgres before this bridge returns identity information.
    $profile = $bridge->callRpc($token, 'api_me', []);
    if ($profile['status'] >= 500) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }
    if ($profile['status'] >= 400 || !is_array($profile['body']) || !isset($profile['body']['id'])) {
        app(\App\ApiOperationAuditLogger::class)->record(
            'supabase',
            'bridge.active_profile',
            'rejected',
            403,
            0,
            null,
            'active_profile_required',
            $request->attributes->get('correlation_id'),
        );
        return response()->json(['message' => 'An active TACResQ profile is required.'], 403);
    }


    return response()->json([
        'id' => $user['id'],
        'email' => $user['email'] ?? null,
    ]);
});

Route::post('/bridge/rpc/{function}', function (
    Request $request,
    string $function,
    \App\SupabaseBridge $bridge
) {
    // Bound untrusted JSON before token verification or upstream work. RPC
    // arguments are JSON objects, not arbitrary top-level arrays.
    abort_if(strlen($request->getContent()) > 32768, 413, 'Request payload is too large.');
    try {
        $decodedArguments = json_decode(
            $request->getContent(),
            false,
            64,
            JSON_THROW_ON_ERROR
        );
    } catch (\JsonException) {
        abort(422, 'RPC arguments must be a valid JSON object.');
    }
    abort_unless(
        $decodedArguments instanceof \stdClass,
        422,
        'RPC arguments must be a JSON object.'
    );
    $arguments = get_object_vars($decodedArguments);

    $allowlist = config('tacru.bridge_rpc_allowlist', []);
    abort_unless(in_array($function, $allowlist, true), 404, 'RPC endpoint not found.');

    $token = $request->bearerToken();
    abort_if (!$token, 401, 'A valid Supabase access token is required.');

    try {
        $user = $bridge->getUser($token);
        if (!$user) {
            return response()->json(['message' => 'The Supabase session is invalid or expired.'], 401);
        }
    } catch (\RuntimeException) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }

    $userRateKey = 'bridge-user:'.hash('sha256', (string) $user['id']);
    if (RateLimiter::tooManyAttempts($userRateKey, 120)) {
        return response()->json(['message' => 'Too many requests. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($userRateKey));
    }
    RateLimiter::hit($userRateKey, 60);

    // Verify current application account state with the original caller JWT.
    // Never infer the TACResQ profile from Auth email or user metadata.
    $profile = $bridge->callRpc($token, 'api_me', []);
    if ($profile['status'] >= 500) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }
    if ($profile['status'] >= 400 || !is_array($profile['body']) || !isset($profile['body']['id'])) {
        app(\App\ApiOperationAuditLogger::class)->record(
            'supabase',
            'bridge.active_profile',
            'rejected',
            403,
            0,
            null,
            'active_profile_required',
            $request->attributes->get('correlation_id'),
        );
        return response()->json(['message' => 'An active TACResQ profile is required.'], 403);
    }


    $result = $bridge->callRpc($token, $function, $arguments);
    if ($result['status'] >= 500) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }

    if ($result['status'] >= 400) {
        $body = is_array($result['body']) ? $result['body'] : [];
        $message = $body['message'] ?? $body['msg'] ?? 'The request could not be completed.';
        $error = ['message' => mb_substr((string) $message, 0, 300)];
        if (isset($body['code'])) {
            $error['code'] = (string) $body['code'];
        }

        return response()->json($error, $result['status']);
    }

    return response()->json($result['body'], $result['status']);
});

});

// One-off deployment diagnostic. It accepts no caller-supplied coordinates or payload;
// use only the synthetic in-area points from backend/routing/smoke-test.sh. Keep the
// independent token unset except during an explicitly authorized smoke test.
Route::post('/geoapify-smoke-test', function (Request $request) {
    $json = static fn (array $body, int $status) => response()->json($body, $status)
        ->header('Cache-Control', 'no-store, private');
    $expectedToken = (string) config('tacru.geoapify_smoke_test_token', '');
    $providedToken = (string) $request->header('X-TACResQ-Smoke-Token', '');
    if (strlen($expectedToken) < 32 || $providedToken === '' || !hash_equals($expectedToken, $providedToken)) {
        return $json(['message' => 'Not found.'], 404);
    }
    if ($request->query->count() > 0 || $request->getContent() !== '') {
        return $json(['message' => 'This smoke test accepts no caller-supplied data.'], 422);
    }

    $apiKey = trim((string) config('tacru.geoapify_api_key', ''));
    if ($apiKey === '') {
        return $json(['message' => 'Geoapify is not configured on this service.'], 503);
    }

    // Match the Route Planner API's JSON POST format. This minimal plan has only two
    // fixed synthetic locations: one agent start and one job. Coordinates use
    // GeoJSON [longitude, latitude] order. The key is required in the query string;
    // the fixed-host URL and exception are never logged or returned to the caller.
    $plannerPayload = [
        'mode' => 'drive',
        'agents' => [[
            'id' => 'tacresq-smoke-agent',
            'start_location' => [125.0030, 11.2443],
            'pickup_capacity' => 1,
        ]],
        'jobs' => [[
            'id' => 'tacresq-smoke-job',
            'location' => [125.0200, 11.2500],
            'duration' => 60,
            'pickup_amount' => 1,
        ]],
    ];
    $plannerUrl = 'https://api.geoapify.com/v1/routeplanner?'.http_build_query(
        ['apiKey' => $apiKey],
        '',
        '&',
        PHP_QUERY_RFC3986
    );

    try {
        $upstream = \Illuminate\Support\Facades\Http::acceptJson()
            ->asJson()
            ->connectTimeout(5)
            ->timeout(12)
            ->withOptions(['allow_redirects' => false])
            ->post($plannerUrl, $plannerPayload);
    } catch (\Throwable) {
        // Do not serialize the exception: HTTP client exception messages can contain the URL.
        return $json(['message' => 'Geoapify Route Planner test request failed.'], 502);
    }

    if ($upstream->status() === 429) {
        return $json(['message' => 'Geoapify Route Planner is rate-limited or out of credits.'], 503);
    }
    if (in_array($upstream->status(), [400, 401, 403], true)) {
        return $json(['message' => 'Geoapify rejected the Route Planner request or configured key.'], 502);
    }
    if (!$upstream->successful()) {
        return $json(['message' => 'Geoapify Route Planner test request failed.'], 502);
    }

    $body = $upstream->json();
    $features = is_array($body) && ($body['type'] ?? null) === 'FeatureCollection'
        && is_array($body['features'] ?? null) ? $body['features'] : null;
    $jobsPlanned = 0;
    foreach ($features ?? [] as $feature) {
        if (!is_array($feature)) {
            continue;
        }
        $properties = is_array($feature['properties'] ?? null) ? $feature['properties'] : [];
        $actions = is_array($properties['actions'] ?? null) ? $properties['actions'] : [];
        foreach ($actions as $action) {
            if (is_array($action) && ($action['type'] ?? null) === 'job') {
                $jobsPlanned++;
            }
        }
    }
    if ($features === null || $features === [] || $jobsPlanned < 1) {
        return $json(['message' => 'Geoapify returned an invalid Route Planner result.'], 502);
    }

    return $json([
        'status' => 'passed',
        'provider' => 'Geoapify Route Planner',
        'coordinates' => 'synthetic test points only',
        'agents_requested' => 1,
        'jobs_requested' => 1,
        'routes_returned' => count($features),
        'jobs_planned' => $jobsPlanned,
    ], 200);
})->middleware('throttle:5,1');


// Reports only the server-selected routing recipient so the app can name it before consent.
// This endpoint accepts no incident identifier or coordinates and is restricted to field roles.
Route::get('/route-preview/provider', function (Request $request, \App\SupabaseBridge $bridge) {
    if (!config('tacru.route_preview_enabled', false)) {
        return response()->json(['message' => 'In-app routing is not enabled for this environment.'], 503);
    }
    $token = $request->bearerToken();
    abort_if (!$token, 401, 'A valid Supabase access token is required.');

    try {
        $authUser = $bridge->getUser($token);
        if (!$authUser) {
            return response()->json(['message' => 'The Supabase session is invalid or expired.'], 401);
        }
    } catch (\RuntimeException) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }

    // This endpoint performs another authenticated Supabase RPC; bound it per
    // verified account before making the additional upstream request.
    $providerRateKey = 'route-preview-provider-user:'.hash('sha256', (string) $authUser['id']);
    if (RateLimiter::tooManyAttempts($providerRateKey, 30)) {
        return response()->json(['message' => 'Too many route-provider checks. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($providerRateKey))
            ->header('Cache-Control', 'no-store, private');
    }
    RateLimiter::hit($providerRateKey, 60);

    $profileResult = $bridge->callRpc($token, 'api_me', []);
    if ($profileResult['status'] >= 500) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }
    $profile = $profileResult['body'];
    if ($profileResult['status'] >= 400 || !is_array($profile)
        || !in_array($profile['role'] ?? null, ['responder', 'driver'], true)) {
        return response()->json(['message' => 'This account cannot request a route preview.'], 403);
    }

    $providerId = match (strtolower(trim((string) config('tacru.route_provider', 'osrm')))) {
        'geoapify' => 'geoapify',
        'osrm' => 'private_osrm',
        default => null,
    };
    if ($providerId === null) {
        return response()->json(['message' => 'The configured routing provider is unavailable.'], 503);
    }

    return response()->json(['provider_id' => $providerId]);
});

// Authenticated route preview. The request goes only to the explicitly configured provider;
// incident text, profile data, raw URLs, and upstream response bodies are never logged.
Route::post('/route-preview', function (Request $request, \App\SupabaseBridge $bridge, \App\ApiOperationAuditLogger $auditLogger) {
    if (!config('tacru.route_preview_enabled', false)) {
        return response()->json(['message' => 'In-app routing is not enabled for this environment.'], 503);
    }
    $token = $request->bearerToken();
    abort_if (!$token, 401, 'A valid Supabase access token is required.');

    try {
        $authUser = $bridge->getUser($token);
        if (!$authUser) {
            return response()->json(['message' => 'The Supabase session is invalid or expired.'], 401);
        }
    } catch (\RuntimeException) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }

    $userRateKey = 'route-preview-user:'.hash('sha256', (string) $authUser['id']);
    if (RateLimiter::tooManyAttempts($userRateKey, 10)) {
        return response()->json(['message' => 'Too many route requests. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($userRateKey));
    }
    RateLimiter::hit($userRateKey, 60);

    $data = $request->validate([
        'incident_id' => ['required', 'integer', 'min:1'],
        'latitude' => ['required', 'numeric', 'between:-90,90'],
        'longitude' => ['required', 'numeric', 'between:-180,180'],
        'expected_provider_id' => ['sometimes', 'nullable', 'string', 'in:geoapify,private_osrm'],
    ]);
    $originLat = (float) $data['latitude'];
    $originLon = (float) $data['longitude'];
    abort_unless (is_finite($originLat) && is_finite($originLon), 422, 'Valid coordinates are required.');

    $profileResult = $bridge->callRpc($token, 'api_me', []);
    if ($profileResult['status'] >= 500) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }
    $profile = $profileResult['body'];
    if ($profileResult['status'] >= 400 || !is_array($profile) || !in_array($profile['role'] ?? null, ['responder', 'driver'], true)) {
        return response()->json(['message' => 'This account cannot request a route preview.'], 403);
    }
    $profileId = $profile['id'] ?? null;
    if (!is_int($profileId) && !(is_string($profileId) && ctype_digit($profileId))) {
        return response()->json(['message' => 'The account profile is unavailable.'], 403);
    }
    $profileId = (int) $profileId;

    $incidentsResult = $bridge->callRpc($token, 'api_my_incidents', []);
    if ($incidentsResult['status'] >= 500) {
        return response()->json(['message' => 'Supabase is temporarily unavailable. Please retry.'], 502);
    }
    if ($incidentsResult['status'] >= 400 || !is_array($incidentsResult['body'])) {
        return response()->json(['message' => 'Unable to verify incident assignment.'], 502);
    }
    $incident = null;
    foreach ($incidentsResult['body'] as $candidate) {
        if (is_array($candidate) && (string) ($candidate['id'] ?? '') === (string) $data['incident_id']) {
            $incident = $candidate;
            break;
        }
    }
    $assignedIds = is_array($incident['assigned_user_ids'] ?? null) ? $incident['assigned_user_ids'] : [];
    $assigned = in_array($profileId, array_map('intval', $assignedIds), true);
    $ownStatus = null;
    $assignments = is_array($incident['assigned_user_statuses'] ?? null) ? $incident['assigned_user_statuses'] : [];
    foreach ($assignments as $assignment) {
        if (is_array($assignment) && (string) ($assignment['user_id'] ?? '') === (string) $profileId) {
            $ownStatus = $assignment['status'] ?? null;
            break;
        }
    }
    if (!$incident || !$assigned
        || !in_array($incident['status'] ?? null, ['assigned', 'en_route'], true)
        || !in_array($ownStatus, ['assigned', 'en_route'], true)) {
        return response()->json(['message' => 'Incident not found or not available for route preview.'], 404);
    }

    $destinationLat = $incident['latitude'] ?? null;
    $destinationLon = $incident['longitude'] ?? null;
    if (!is_numeric($destinationLat) || !is_numeric($destinationLon)
        || !is_finite((float) $destinationLat) || !is_finite((float) $destinationLon)
        || (float) $destinationLat < -90 || (float) $destinationLat > 90
        || (float) $destinationLon < -180 || (float) $destinationLon > 180) {
        return response()->json(['message' => 'Incident location is unavailable for route preview.'], 422);
    }

    $provider = strtolower(trim((string) config('tacru.route_provider', 'osrm')));
    $providerId = match ($provider) {
        'geoapify' => 'geoapify',
        'osrm' => 'private_osrm',
        default => null,
    };
    if (($data['expected_provider_id'] ?? null) !== null
        && $data['expected_provider_id'] !== $providerId) {
        return response()->json([
            'message' => 'The routing provider changed. Verify the recipient again before requesting a route.',
        ], 409);
    }
    if ($provider === 'geoapify') {
        $apiKey = trim((string) config('tacru.geoapify_api_key', ''));
        if ($apiKey === '') {
            return response()->json(['message' => 'Geoapify routing is not configured.'], 503);
        }

        // Geoapify requires the API key in the query string. This fixed EU endpoint
        // receives the two route coordinates; never log the URL or exception details.
        $providerStartedAt = hrtime(true);
        try {
            $upstream = \Illuminate\Support\Facades\Http::acceptJson()
                ->connectTimeout(5)
                ->timeout(12)
                ->withOptions(['allow_redirects' => false])
                ->get('https://api-eu.geoapify.com/v1/routing', [
                    'waypoints' => number_format($originLat, 7, '.', '').','.number_format($originLon, 7, '.', '')
                        .'|'.number_format((float) $destinationLat, 7, '.', '').','.number_format((float) $destinationLon, 7, '.', ''),
                    'mode' => 'drive',
                    'format' => 'json',
                    'units' => 'metric',
                    'lang' => 'en',
                    'apiKey' => $apiKey,
                ]);
        } catch (\Throwable) {
            $auditLogger->record('geoapify', 'route.preview', 'timeout', 502, (int) ((hrtime(true) - $providerStartedAt) / 1_000_000), null, 'upstream_unavailable');
            return response()->json(['message' => 'Geoapify routing is temporarily unavailable.'], 502);
        }

        $auditLogger->record(
            'geoapify',
            'route.preview',
            $upstream->successful() ? 'success' : ($upstream->status() === 429 ? 'rejected' : 'failure'),
            $upstream->status(),
            (int) ((hrtime(true) - $providerStartedAt) / 1_000_000),
            null,
            $upstream->successful() ? null : 'provider_http_'.$upstream->status(),
        );

        if ($upstream->status() === 429) {
            return response()->json(['message' => 'Geoapify routing is busy or out of credits. Use external Maps instead.'], 503);
        }
        if (in_array($upstream->status(), [401, 403], true)) {
            return response()->json(['message' => 'Geoapify did not accept the configured server key.'], 502);
        }
        if (!$upstream->successful()) {
            return response()->json(['message' => 'Geoapify could not calculate this route. Use external Maps instead.'], 502);
        }

        $body = $upstream->json();
        $route = is_array($body) && is_array($body['results'] ?? null) ? ($body['results'][0] ?? null) : null;
        $distance = is_array($route) ? ($route['distance'] ?? null) : null;
        $duration = is_array($route) ? ($route['time'] ?? null) : null;
        $segments = is_array($route) ? ($route['geometry'] ?? null) : null;
        if (!is_numeric($distance) || !is_numeric($duration)
            || !is_finite((float) $distance) || (float) $distance <= 0
            || !is_finite((float) $duration) || (float) $duration <= 0
            || !is_array($segments) || count($segments) < 1 || count($segments) > 10) {
            return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
        }

        $geometry = [];
        foreach ($segments as $segment) {
            if (!is_array($segment) || count($segment) < 2) {
                return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
            }
            foreach ($segment as $point) {
                $longitude = is_array($point) ? ($point['lon'] ?? null) : null;
                $latitude = is_array($point) ? ($point['lat'] ?? null) : null;
                if (!is_numeric($longitude) || !is_numeric($latitude)
                    || !is_finite((float) $longitude) || !is_finite((float) $latitude)
                    || (float) $longitude < -180 || (float) $longitude > 180
                    || (float) $latitude < -90 || (float) $latitude > 90) {
                    return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
                }
                $last = count($geometry) > 0 ? $geometry[count($geometry) - 1] : null;
                if ($last !== null && $last['longitude'] === (float) $longitude && $last['latitude'] === (float) $latitude) {
                    continue;
                }
                $geometry[] = ['latitude' => (float) $latitude, 'longitude' => (float) $longitude];
                if (count($geometry) > 5000) {
                    return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
                }
            }
        }
        if (count($geometry) < 2) {
            return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
        }

        $legs = $route['legs'] ?? null;
        if (!is_array($legs) || count($legs) > 10) {
            return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
        }
        $instructions = [];
        foreach ($legs as $leg) {
            $steps = is_array($leg) ? ($leg['steps'] ?? null) : null;
            if (!is_array($steps) || count($steps) + count($instructions) > 200) {
                return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
            }
            foreach ($steps as $step) {
                $instruction = is_array($step) ? ($step['instruction'] ?? null) : null;
                $text = is_array($instruction) ? trim((string) ($instruction['text'] ?? '')) : '';
                $stepDistance = is_array($step) ? ($step['distance'] ?? null) : null;
                $stepDuration = is_array($step) ? ($step['time'] ?? null) : null;
                if (!is_numeric($stepDistance) || !is_numeric($stepDuration)
                    || !is_finite((float) $stepDistance) || (float) $stepDistance < 0
                    || !is_finite((float) $stepDuration) || (float) $stepDuration < 0
                    || mb_strlen($text) > 300) {
                    return response()->json(['message' => 'Geoapify returned an invalid route. Use external Maps instead.'], 502);
                }
                if ($text !== '') {
                    $instructions[] = [
                        'text' => $text,
                        'distance_meters' => (float) $stepDistance,
                        'duration_seconds' => (float) $stepDuration,
                    ];
                }
            }
        }

        return response()->json([
            'geometry' => $geometry,
            'distance_meters' => (float) $distance,
            'duration_seconds' => (float) $duration,
            'instructions' => $instructions,
        ]);
    }
    if ($provider !== 'osrm') {
        return response()->json(['message' => 'In-app routing is not configured.'], 503);
    }

    // Accept only a single-label internal hostname. Reject dotted public/loopback/IP
    // targets so coordinates cannot be sent to an unintended network service.
    $routerBaseUrl = rtrim(trim((string) config('tacru.osrm_base_url', '')), '/');
    $routerParts = $routerBaseUrl !== '' ? parse_url($routerBaseUrl) : false;
    $routerHost = is_array($routerParts) ? strtolower(trim((string) ($routerParts['host'] ?? ''), '[]')) : '';
    $isPrivateHostname = $routerHost !== ''
        && !str_contains($routerHost, '.')
        && filter_var($routerHost, FILTER_VALIDATE_IP) === false;
    $hasNoUrlCredentialsOrOptions = is_array($routerParts)
        && !array_key_exists('user', $routerParts)
        && !array_key_exists('pass', $routerParts)
        && !array_key_exists('query', $routerParts)
        && !array_key_exists('fragment', $routerParts)
        && in_array($routerParts['path'] ?? '', ['', '/'], true);
    if (!is_array($routerParts) || ($routerParts['scheme'] ?? null) !== 'http'
        || !$isPrivateHostname || !$hasNoUrlCredentialsOrOptions) {
        return response()->json(['message' => 'In-app routing is not configured.'], 503);
    }

    $providerStartedAt = hrtime(true);
    try {
        // POST keeps precise coordinates out of the request URL. No bearer token,
        // incident ID, narrative, or personal information is sent to OSRM.
        $upstream = \Illuminate\Support\Facades\Http::acceptJson()
            ->asJson()
            ->connectTimeout(3)
            ->timeout(12)
            ->post($routerBaseUrl.'/route/v1/driving', [
                'coordinates' => [[$originLon, $originLat], [(float) $destinationLon, (float) $destinationLat]],
                'steps' => true,
                'overview' => 'full',
                'geometries' => 'geojson',
                'alternatives' => false,
            ]);
    } catch (\Throwable) {
        $auditLogger->record('private_osrm', 'route.preview', 'timeout', 502, (int) ((hrtime(true) - $providerStartedAt) / 1_000_000), null, 'upstream_unavailable');
        return response()->json(['message' => 'Private route service is temporarily unavailable.'], 502);
    }
    $auditLogger->record(
        'private_osrm',
        'route.preview',
        $upstream->successful() ? 'success' : ($upstream->status() === 429 ? 'rejected' : 'failure'),
        $upstream->status(),
        (int) ((hrtime(true) - $providerStartedAt) / 1_000_000),
        null,
        $upstream->successful() ? null : 'provider_http_'.$upstream->status(),
    );
    $body = $upstream->json();
    if (is_array($body) && in_array($body['code'] ?? null, ['NoRoute', 'NoSegment'], true)) {
        return response()->json(['message' => 'No route could be calculated. Confirm the destination with dispatch or use external Maps.'], 422);
    }
    if ($upstream->status() === 429) {
        return response()->json(['message' => 'Private route service is busy. Please retry shortly.'], 503);
    }
    if (!$upstream->successful()) {
        return response()->json(['message' => 'Private route service could not calculate this route.'], 502);
    }

    $route = is_array($body) && ($body['code'] ?? null) === 'Ok' ? ($body['routes'][0] ?? null) : null;
    $coordinates = is_array($route) ? ($route['geometry']['coordinates'] ?? null) : null;
    $distance = is_array($route) ? ($route['distance'] ?? null) : null;
    $duration = is_array($route) ? ($route['duration'] ?? null) : null;
    if (!is_array($route) || ($route['geometry']['type'] ?? null) !== 'LineString'
        || !is_array($coordinates) || count($coordinates) < 2 || count($coordinates) > 5000
        || !is_numeric($distance) || !is_numeric($duration)
        || !is_finite((float) $distance) || (float) $distance <= 0
        || !is_finite((float) $duration) || (float) $duration <= 0) {
        return response()->json(['message' => 'Private route service returned an invalid route.'], 502);
    }

    $geometry = [];
    foreach ($coordinates as $coordinate) {
        if (!is_array($coordinate) || count($coordinate) < 2 || !is_numeric($coordinate[0] ?? null)
            || !is_numeric($coordinate[1] ?? null) || !is_finite((float) $coordinate[0])
            || !is_finite((float) $coordinate[1]) || (float) $coordinate[0] < -180
            || (float) $coordinate[0] > 180 || (float) $coordinate[1] < -90 || (float) $coordinate[1] > 90) {
            return response()->json(['message' => 'Private route service returned an invalid route.'], 502);
        }
        $geometry[] = ['latitude' => (float) $coordinate[1], 'longitude' => (float) $coordinate[0]];
    }

    $legs = $route['legs'] ?? null;
    if (!is_array($legs) || count($legs) > 10) {
        return response()->json(['message' => 'Private route service returned an invalid route.'], 502);
    }
    $instructions = [];
    foreach ($legs as $leg) {
        $steps = is_array($leg) ? ($leg['steps'] ?? null) : null;
        if (!is_array($steps) || count($steps) + count($instructions) > 200) {
            return response()->json(['message' => 'Private route service returned an invalid route.'], 502);
        }
        foreach ($steps as $step) {
            $maneuver = is_array($step) ? ($step['maneuver'] ?? []) : [];
            $type = is_array($maneuver) ? (string) ($maneuver['type'] ?? '') : '';
            $modifier = is_array($maneuver) ? trim((string) ($maneuver['modifier'] ?? '')) : '';
            $name = is_array($step) ? trim((string) ($step['name'] ?? '')) : '';
            $distanceMeters = is_array($step) ? ($step['distance'] ?? null) : null;
            $durationSeconds = is_array($step) ? ($step['duration'] ?? null) : null;
            if (!is_numeric($distanceMeters) || !is_numeric($durationSeconds)
                || !is_finite((float) $distanceMeters) || (float) $distanceMeters < 0
                || !is_finite((float) $durationSeconds) || (float) $durationSeconds < 0) {
                return response()->json(['message' => 'Private route service returned an invalid route.'], 502);
            }
            $action = match ($type) {
                'depart' => 'Start',
                'arrive' => 'Arrive at destination',
                'turn' => 'Turn'.($modifier !== '' ? ' '.$modifier : ''),
                'fork' => 'Keep'.($modifier !== '' ? ' '.$modifier : ''),
                'merge' => 'Merge',
                'roundabout', 'rotary' => 'At the roundabout',
                'on ramp' => 'Take the ramp',
                'off ramp' => 'Take the exit',
                default => 'Continue',
            };
            $text = $type === 'arrive' || $name === '' ? $action : $action.' onto '.$name;
            if (mb_strlen($text) > 300) {
                return response()->json(['message' => 'Private route service returned an invalid route.'], 502);
            }
            $instructions[] = [
                'text' => $text,
                'distance_meters' => (float) $distanceMeters,
                'duration_seconds' => (float) $durationSeconds,
            ];
        }
    }

    return response()->json([
        'geometry' => $geometry,
        'distance_meters' => (float) $distance,
        'duration_seconds' => (float) $duration,
        'instructions' => $instructions,
    ]);
})->middleware('throttle:600,1');


// Local Laravel Admin TOTP enrollment is deliberately separate from Supabase Auth.
// The secret is returned once for authenticator setup and stored only encrypted.
Route::middleware(['auth:sanctum', 'account.active'])->prefix('admin/security/totp')->group(function () {
    Route::post('/enroll', function (Request $request) {
        $user = $request->user();
        abort_unless($user !== null && $user->role === 'admin', 403, 'Administrator access is required.');

        $data = $request->validate([
            'current_password' => ['required', 'string', 'max:72'],
        ]);
        $result = DB::transaction(function () use ($user, $data) {
            $lockedUser = User::whereKey($user->id)->lockForUpdate()->firstOrFail();
            if ($lockedUser->role !== 'admin'
                || $lockedUser->is_suspended
                || !Hash::check($data['current_password'], $lockedUser->password)) {
                return null;
            }

            $existing = DB::table('local_admin_totp_credentials')
                ->where('user_id', $lockedUser->id)
                ->lockForUpdate()
                ->first();
            abort_if($existing !== null && $existing->confirmed_at !== null, 409, 'A confirmed authenticator is already enrolled. Contact the account recovery administrator to replace it.');

            $totp = app(\App\Services\LocalAdminTotpService::class);
            $secret = $totp->generateSecret();
            DB::table('local_admin_totp_credentials')->updateOrInsert(
                ['user_id' => $lockedUser->id],
                [
                    'secret_ciphertext' => $totp->encryptSecret($secret),
                    'confirmed_at' => null,
                    'expires_at' => now()->addMinutes(10),
                    'last_used_step' => null,
                    'last_used_code_hash' => null,
                    'created_at' => now(),
                    'updated_at' => now(),
                ],
            );

            return [
                'secret' => $secret,
                'label' => 'TACResQ:'.$lockedUser->email,
            ];
        }, 3);

        if ($result === null) {
            return response()->json(['message' => 'Administrator re-authentication failed.'], 403)
                ->header('Cache-Control', 'no-store, private');
        }

        $label = rawurlencode($result['label']);
        $issuer = rawurlencode('TACResQ');
        $uri = 'otpauth://totp/'.$label.'?secret='.$result['secret'].'&issuer='.$issuer.'&algorithm=SHA1&digits=6&period=30';

        return response()->json([
            'secret' => $result['secret'],
            'otpauth_uri' => $uri,
            'message' => 'Add this account to an authenticator app, then confirm with its current six-digit code. The secret is shown only in this response.',
        ])->header('Cache-Control', 'no-store, private');
    })->middleware('throttle:3,1');

    Route::post('/confirm', function (Request $request) {
        $user = $request->user();
        abort_unless($user !== null && $user->role === 'admin', 403, 'Administrator access is required.');
        $data = $request->validate([
            'current_password' => ['required', 'string', 'max:72'],
            'totp_code' => ['required', 'string', 'regex:/^\d{6}$/'],
        ]);

        $confirmed = DB::transaction(function () use ($user, $data) {
            $lockedUser = User::whereKey($user->id)->lockForUpdate()->firstOrFail();
            if ($lockedUser->role !== 'admin'
                || $lockedUser->is_suspended
                || !Hash::check($data['current_password'], $lockedUser->password)) {
                return null;
            }

            return app(\App\Services\LocalAdminTotpService::class)
                ->confirmEnrollment($lockedUser, $data['totp_code']);
        }, 3);

        if ($confirmed === null) {
            return response()->json(['message' => 'Administrator re-authentication failed.'], 403)
                ->header('Cache-Control', 'no-store, private');
        }

        if (!$confirmed) {
            return response()->json(['message' => 'The authenticator code is invalid or the enrollment has expired.'], 422)
                ->header('Cache-Control', 'no-store, private');
        }

        return response()->json(['enabled' => true, 'message' => 'Authenticator verification is enabled for this local Admin account.'])
            ->header('Cache-Control', 'no-store, private');
    })->middleware('throttle:5,1');
});
