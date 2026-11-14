<?php

namespace App;

/**
 * Classifies the structured error body returned by Firebase HTTP v1.
 *
 * The details array is important: INVALID_ARGUMENT may describe an invalid
 * message field (google.rpc.BadRequest) or an invalid registration
 * (google.firebase.fcm.v1.FcmError). Do not delete tokens from HTTP status
 * alone when those cases are distinguishable.
 */
final class FcmHttpV1ErrorClassifier
{
    /**
     * @return array{invalid_token:bool,retryable:bool,error_code:string}
     */
    public static function classify(int $httpStatus, array $error): array
    {
        $errorStatus = $error['status'] ?? null;
        $errorStatus = is_string($errorStatus) ? $errorStatus : null;
        $errorDetails = $error['details'] ?? [];
        $fcmErrorCode = null;
        $hasBadRequestDetails = false;

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
            'invalid_token' => $invalidToken,
            'retryable' => $retryable,
            'error_code' => $errorCode ?? 'HTTP_'.$httpStatus,
        ];
    }
}
