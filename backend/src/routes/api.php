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

    $userRateKey = 'bridge-user:'.$user['id'];
    if (RateLimiter::tooManyAttempts($userRateKey, 120)) {
        return response()->json(['message' => 'Too many requests. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($userRateKey));
    }
    RateLimiter::hit($userRateKey, 60);

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

    $userRateKey = 'bridge-user:'.$user['id'];
    if (RateLimiter::tooManyAttempts($userRateKey, 120)) {
        return response()->json(['message' => 'Too many requests. Please retry shortly.'], 429)
            ->header('Retry-After', (string) RateLimiter::availableIn($userRateKey));
    }
    RateLimiter::hit($userRateKey, 60);

    $result = $bridge->callRpc($token, $function, $request->json()->all());
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

    // Geoapify requires apiKey in its query string. This URL is fixed to the EU
    // endpoint; redirects are disabled and the request/exception URL is never logged.
    // Waypoints are the same synthetic points used by the local OSRM smoke test,
    // expressed in Geoapify's latitude,longitude order.
    try {
        $upstream = \Illuminate\Support\Facades\Http::acceptJson()
            ->connectTimeout(5)
            ->timeout(12)
            ->withOptions(['allow_redirects' => false])
            ->get('https://api-eu.geoapify.com/v1/routing', [
                'waypoints' => '11.2443,125.0030|11.2500,125.0200',
                'mode' => 'drive',
                'format' => 'json',
                'apiKey' => $apiKey,
            ]);
    } catch (\Throwable) {
        // Do not serialize the exception: HTTP client exception messages can contain the URL.
        return $json(['message' => 'Geoapify test request failed.'], 502);
    }

    if ($upstream->status() === 429) {
        return $json(['message' => 'Geoapify is rate-limited or out of credits.'], 503);
    }
    if (in_array($upstream->status(), [400, 401, 403], true)) {
        return $json(['message' => 'Geoapify did not accept the configured server key.'], 502);
    }
    if (!$upstream->successful()) {
        return $json(['message' => 'Geoapify test request failed.'], 502);
    }

    $body = $upstream->json();
    $route = is_array($body) && is_array($body['results'] ?? null) ? ($body['results'][0] ?? null) : null;
    $distance = is_array($route) ? ($route['distance'] ?? null) : null;
    $duration = is_array($route) ? ($route['time'] ?? null) : null;
    if (!is_numeric($distance) || !is_numeric($duration)
        || !is_finite((float) $distance) || (float) $distance <= 0
        || !is_finite((float) $duration) || (float) $duration <= 0) {
        return $json(['message' => 'Geoapify returned an invalid smoke-test route.'], 502);
    }

    return $json([
        'status' => 'passed',
        'provider' => 'Geoapify EU',
        'coordinates' => 'synthetic test points only',
        'distance_meters' => (float) $distance,
        'duration_seconds' => (float) $duration,
    ], 200);
})->middleware('throttle:5,1');


// Authenticated route preview. The request goes only to a configured private OSRM service;
// incident text, profile data, and upstream response details are never forwarded or logged.
Route::post('/route-preview', function (Request $request, \App\SupabaseBridge $bridge) {
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

    try {
        // POST keeps precise coordinates out of the request URL. No bearer token,
        // incident ID, narrative, or personal information is sent to OSRM.
        $upstream = \Illuminate\Support\Facades\Http::acceptJson()
            ->asJson()
            ->timeout(12)
            ->post($routerBaseUrl.'/route/v1/driving', [
                'coordinates' => [[$originLon, $originLat], [(float) $destinationLon, (float) $destinationLat]],
                'steps' => true,
                'overview' => 'full',
                'geometries' => 'geojson',
                'alternatives' => false,
            ]);
    } catch (\Throwable) {
        return response()->json(['message' => 'Private route service is temporarily unavailable.'], 502);
    }
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
