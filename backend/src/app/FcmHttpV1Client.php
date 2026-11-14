<?php

namespace App;

use Google\Auth\ApplicationDefaultCredentials;
use Illuminate\Support\Facades\Http;
use RuntimeException;

final class FcmHttpV1Client
{
    private const MESSAGING_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

    /**
     * Validate that ADC can mint a Firebase Messaging token without sending a message.
     * Never return or log the credential or access token.
     */
    public function assertCredentialsAvailable(): void
    {
        $credentials = ApplicationDefaultCredentials::getCredentials([self::MESSAGING_SCOPE]);
        $authToken = $credentials->fetchAuthToken();
        $accessToken = is_array($authToken) ? ($authToken['access_token'] ?? null) : null;
        if (!is_string($accessToken) || $accessToken === '') {
            throw new RuntimeException('ADC did not provide an FCM access token.');
        }
    }

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
                'retryable' => false,
                'status' => $response->status(),
                'retry_after_seconds' => null,
                'provider_message_name' => is_string($name) ? $name : null,
                'error_code' => null,
            ];
        }

        $httpStatus = $response->status();
        $errorCode = $response->json('error.status');
        $errorCode = is_string($errorCode) ? $errorCode : null;
        $retryable = in_array($httpStatus, [429, 500, 502, 503, 504], true)
            || in_array($errorCode, ['QUOTA_EXCEEDED', 'UNAVAILABLE', 'INTERNAL'], true);

        return [
            'sent' => false,
            // FCM INVALID_ARGUMENT can describe a malformed payload. Only
            // UNREGISTERED unambiguously means this registration token is stale.
            'invalid_token' => $errorCode === 'UNREGISTERED',
            'retryable' => $retryable,
            'status' => $httpStatus,
            'retry_after_seconds' => $this->retryAfterSeconds($response->header('Retry-After')),
            'provider_message_name' => null,
            // Keep provider details out of logs and persisted records.
            'error_code' => $errorCode ?? 'HTTP_'.$httpStatus,
        ];
    }

    private function retryAfterSeconds(?string $header): ?int
    {
        $header = trim((string) $header);
        if ($header === '') {
            return null;
        }
        if (ctype_digit($header)) {
            return min(86400, (int) $header);
        }

        $timestamp = strtotime($header);
        if ($timestamp === false) {
            return null;
        }

        return min(86400, max(0, $timestamp - time()));
    }
}
