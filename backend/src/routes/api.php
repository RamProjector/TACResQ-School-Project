<?php

use App\Models\Incident;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
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
    $credentials = $request->validate(['email' => ['required', 'email'], 'password' => ['required', 'string']]);
    $user = User::where('email', $credentials['email'])->first();
    if (!$user || !Hash::check($credentials['password'], $user->password)) {
        return response()->json(['message' => 'The provided credentials are incorrect.'], 422);
    }
    return response()->json(['token' => $user->createToken('android')->plainTextToken, 'user' => $userJson($user)]);
});

Route::post('/register', function (Request $request) use ($userJson) {
    $data = $request->validate([
        'name' => ['required', 'string', 'max:120'], 'email' => ['required', 'email', 'max:255', 'unique:users,email'],
        'phone' => ['nullable', 'string', 'max:40'], 'password' => ['required', 'string', 'min:8', 'max:72'],
    ]);
    $user = User::create([...$data, 'password' => Hash::make($data['password']), 'role' => 'requester', 'duty_status' => null]);
    return response()->json(['token' => $user->createToken('android')->plainTextToken, 'user' => $userJson($user)], 201);
});

Route::middleware('auth:sanctum')->group(function () use ($userJson, $incidentJson, $onlyRoles) {
    Route::post('/logout', fn (Request $request) => tap(response()->noContent(), fn () => $request->user()->currentAccessToken()?->delete()));
    Route::get('/me', fn (Request $request) => response()->json($userJson($request->user())));
    Route::patch('/me/duty-status', function (Request $request) use ($userJson, $onlyRoles) {
        $onlyRoles($request, ['responder', 'driver']);
        $data = $request->validate(['duty_status' => ['required', Rule::in(['available', 'off_duty'])]]);
        $active = Incident::where('assigned_to', $request->user()->id)->whereIn('status', ['assigned', 'en_route', 'on_scene', 'transporting'])->exists();
        abort_if($active, 422, 'Duty status cannot be changed while an incident is active.');
        $request->user()->update($data);
        return response()->json($userJson($request->user()->fresh()));
    });
    Route::get('/incidents/mine', function (Request $request) use ($incidentJson) {
        $user = $request->user();
        $query = Incident::query();
        if ($user->role === 'requester') $query->where('requester_id', $user->id);
        elseif (in_array($user->role, ['responder', 'driver'], true)) $query->where('assigned_to', $user->id);
        else abort(403, 'This account has no personal incident list.');
        return response()->json($query->latest()->get()->map($incidentJson)->values());
    });
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
        $onlyRoles($request, ['responder', 'driver']);
        abort_unless((int) $incident->assigned_to === (int) $request->user()->id, 403, 'This incident is not assigned to you.');
        $data = $request->validate(['status' => ['required', Rule::in(['en_route', 'on_scene', 'transporting', 'resolved'])]]);
        $transitions = ['assigned' => ['en_route'], 'en_route' => ['on_scene'], 'on_scene' => ['transporting', 'resolved'], 'transporting' => ['resolved']];
        abort_unless(in_array($data['status'], $transitions[$incident->status] ?? [], true), 422, 'Invalid incident status transition.');
        DB::transaction(function () use ($incident, $data, $request) {
            $locked = Incident::whereKey($incident->id)->lockForUpdate()->firstOrFail();
            $allowed = ['assigned' => ['en_route'], 'en_route' => ['on_scene'], 'on_scene' => ['transporting', 'resolved'], 'transporting' => ['resolved']];
            abort_unless((int) $locked->assigned_to === (int) $request->user()->id && in_array($data['status'], $allowed[$locked->status] ?? [], true), 422, 'Incident was changed; refresh and retry.');
            $locked->update(['status' => $data['status']]);
            $locked->statusLogs()->create(['status' => $data['status'], 'changed_by' => $request->user()->id]);
            $request->user()->update(['duty_status' => $data['status'] === 'resolved' ? 'available' : $data['status']]);
        });
        return response()->json($incidentJson($incident->fresh()));
    });
    Route::get('/incidents', function (Request $request) use ($incidentJson, $onlyRoles) {
        $onlyRoles($request, ['dispatcher']);
        return response()->json(Incident::latest()->get()->map($incidentJson)->values());
    });
    Route::get('/units', function (Request $request) use ($userJson, $onlyRoles) {
        $onlyRoles($request, ['dispatcher']);
        return response()->json(User::whereIn('role', ['responder', 'driver'])->orderBy('name')->get()->map($userJson)->values());
    });
    Route::post('/incidents/{incident}/assign', function (Request $request, Incident $incident) use ($incidentJson, $onlyRoles) {
        $onlyRoles($request, ['dispatcher']);
        $data = $request->validate(['user_id' => ['required', 'integer', 'exists:users,id']]);
        $updated = DB::transaction(function () use ($incident, $data, $request) {
            $lockedIncident = Incident::whereKey($incident->id)->lockForUpdate()->firstOrFail();
            abort_unless($lockedIncident->status === 'pending', 422, 'Only pending incidents can be assigned.');
            $unit = User::whereKey($data['user_id'])->lockForUpdate()->firstOrFail();
            abort_unless(in_array($unit->role, ['responder', 'driver'], true), 422, 'Selected user is not a field unit.');
            abort_unless($unit->duty_status === 'available', 422, 'Selected unit is not available.');
            $busy = Incident::where('assigned_to', $unit->id)->whereIn('status', ['assigned', 'en_route', 'on_scene', 'transporting'])->exists();
            abort_if($busy, 422, 'Selected unit already has an active incident.');
            $lockedIncident->update(['assigned_to' => $unit->id, 'status' => 'assigned']);
            $lockedIncident->statusLogs()->create(['status' => 'assigned', 'changed_by' => $request->user()->id]);
            $unit->update(['duty_status' => 'available']);
            return $lockedIncident->fresh();
        });
        return response()->json($incidentJson($updated));
    });
});
