<?php

namespace App;

use Google\Auth\ApplicationDefaultCredentials;
use Illuminate\Support\Facades\Http;
use RuntimeException;

final class FcmHttpV1Client
{
    private const MESSAGING_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

    public function send(string $projectId, string $token, array $data): array
    {
        $projectId = trim($projectId);
        if ($projectId === '' || $token === '') {
            throw new RuntimeException('FCM project configuration or token is missing.');
        }

        $credentials = ApplicationDefaultCredentials::getCredentials([self::MESSAGING_SCOPE]);
        $authToken = $credentials->fetchAuthToken();
        $accessToken = is_array($authToken) ? ($authToken['access_token'] ?? null) : null;
        if (!is_string($accessToken) || $accessToken === '') {
            throw new RuntimeException('ADC did not provide an FCM access token.');
        }

        $response = Http::acceptJson()
            ->asJson()
            ->withToken($accessToken)
            ->timeout(max(1, (int) config('tacru.fcm_http_timeout', 8)))
            ->post(
                'https://fcm.googleapis.com/v1/projects/'.rawurlencode($projectId).'/messages:send',
                ['message' => ['token' => $token, 'data' => $data]],
            );

        if ($response->successful()) {
            $name = $response->json('name');
            return [
                'sent' => true,
                'invalid_token' => false,
                'status' => $response->status(),
                'provider_message_name' => is_string($name) ? $name : null,
                'error_code' => null,
            ];
        }

        $errorCode = $response->json('error.status');
        $errorCode = is_string($errorCode) ? $errorCode : null;
        return [
            'sent' => false,
            'invalid_token' => in_array($errorCode, ['UNREGISTERED', 'INVALID_ARGUMENT'], true),
            'status' => $response->status(),
            'provider_message_name' => null,
            // Keep provider details out of logs and persisted records.
            'error_code' => $errorCode ?? 'HTTP_'.$response->status(),
        ];
    }
}
