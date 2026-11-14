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
        $errorStatus = $response->json('error.status');
        $errorStatus = is_string($errorStatus) ? $errorStatus : null;
        $errorDetails = $response->json('error.details');
        $fcmErrorCode = null;
        $hasBadRequestDetails = false;

        // FCM HTTP v1 uses typed details to distinguish a bad message field
        // from an invalid registration. Both can use top-level INVALID_ARGUMENT.
        if (is_array($errorDetails)) {
            foreach ($errorDetails as $detail) {
                if (!is_array($detail)) {
                    continue;
                }

                $detailType = $detail['@type'] ?? null;
                if ($detailType === 'type.googleapis.com/google.firebase.fcm.v1.FcmError') {
                    $detailCode = $detail['errorCode'] ?? null;
                    if (is_string($detailCode)) {
                        $fcmErrorCode = $detailCode;
                    }
                } elseif ($detailType === 'type.googleapis.com/google.rpc.BadRequest') {
                    $hasBadRequestDetails = true;
                }
            }
        }

        $errorCode = $fcmErrorCode ?? $errorStatus;
        $invalidToken = $errorStatus === 'UNREGISTERED'
            || $fcmErrorCode === 'UNREGISTERED'
            || (
                $httpStatus === 400
                && $errorStatus === 'INVALID_ARGUMENT'
                && $fcmErrorCode === 'INVALID_ARGUMENT'
                && !$hasBadRequestDetails
            );
        $retryable = in_array($httpStatus, [429, 500, 502, 503, 504], true)
            || in_array($errorCode, ['QUOTA_EXCEEDED', 'UNAVAILABLE', 'INTERNAL'], true);

        return [
            'sent' => false,
            // INVALID_ARGUMENT is only token-invalid when the typed FCM detail
            // identifies it and no BadRequest field-violation detail is present.
            'invalid_token' => $invalidToken,
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
