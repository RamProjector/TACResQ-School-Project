<?php

namespace App\Services;

use App\Models\User;
use Illuminate\Support\Facades\Crypt;
use Illuminate\Support\Facades\DB;
use InvalidArgumentException;

final class LocalAdminTotpService
{
    private const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    private const PERIOD_SECONDS = 30;
    private const DIGITS = 6;

    public function generateSecret(): string
    {
        return $this->base32Encode(random_bytes(20));
    }

    public function codeForStep(string $base32Secret, int $step): string
    {
        $secret = $this->base32Decode($base32Secret);
        $counter = pack('N2', intdiv($step, 0x100000000), $step % 0x100000000);
        $hash = hash_hmac('sha1', $counter, $secret, true);
        $offset = ord($hash[strlen($hash) - 1]) & 0x0f;
        $binary = unpack('N', substr($hash, $offset, 4))[1] & 0x7fffffff;
        return str_pad((string) ($binary % (10 ** self::DIGITS)), self::DIGITS, '0', STR_PAD_LEFT);
    }


    public function confirmEnrollment(User $user, string $code): bool
    {
        if (!preg_match('/^\\d{6}$/', $code)) {
            return false;
        }

        return DB::transaction(function () use ($user, $code): bool {
            $credential = DB::table('local_admin_totp_credentials')
                ->where('user_id', $user->getAuthIdentifier())
                ->lockForUpdate()
                ->first();

            if ($credential === null || $credential->confirmed_at !== null || $credential->expires_at === null || now()->greaterThan($credential->expires_at)) {
                return false;
            }

            try {
                $secret = Crypt::decryptString($credential->secret_ciphertext);
            } catch (\Throwable $exception) {
                report($exception);
                return false;
            }

            $currentStep = intdiv(now()->timestamp, self::PERIOD_SECONDS);
            foreach ([$currentStep - 1, $currentStep, $currentStep + 1] as $candidateStep) {
                if ($candidateStep < 0) {
                    continue;
                }

                if (hash_equals($this->codeForStep($secret, $candidateStep), $code)) {
                    DB::table('local_admin_totp_credentials')
                        ->where('id', $credential->id)
                        ->whereNull('confirmed_at')
                        ->update([
                            'confirmed_at' => now(),
                            'expires_at' => null,
                            'last_used_step' => $candidateStep,
                            'last_used_code_hash' => hash('sha256', $code),
                            'updated_at' => now(),
                        ]);
                    return true;
                }
            }

            return false;
        }, 3);
    }

    /**
     * Verify and atomically consume one time step. Only confirmed credentials
     * are accepted, and a previously accepted time step can never be replayed.
     */
    public function verifyAndConsume(User $user, string $code): bool
    {
        if (!preg_match('/^\d{6}$/', $code)) {
            return false;
        }

        return DB::transaction(function () use ($user, $code): bool {
            $credential = DB::table('local_admin_totp_credentials')
                ->where('user_id', $user->getAuthIdentifier())
                ->lockForUpdate()
                ->first();

            if ($credential === null || $credential->confirmed_at === null) {
                return false;
            }

            try {
                $secret = Crypt::decryptString($credential->secret_ciphertext);
            } catch (\Throwable $exception) {
                report($exception);
                return false;
            }

            $currentStep = intdiv(now()->timestamp, self::PERIOD_SECONDS);
            $lastUsedStep = $credential->last_used_step === null ? -1 : (int) $credential->last_used_step;
            $codeHash = hash('sha256', $code);
            if ($credential->last_used_code_hash !== null && hash_equals($credential->last_used_code_hash, $codeHash)) {
                return false;
            }

            foreach ([$currentStep - 1, $currentStep, $currentStep + 1] as $candidateStep) {
                if ($candidateStep <= $lastUsedStep || $candidateStep < 0) {
                    continue;
                }

                if (hash_equals($this->codeForStep($secret, $candidateStep), $code)) {
                    DB::table('local_admin_totp_credentials')
                        ->where('id', $credential->id)
                        ->update([
                            'last_used_step' => $candidateStep,
                            'last_used_code_hash' => $codeHash,
                            'updated_at' => now(),
                        ]);
                    return true;
                }
            }

            return false;
        }, 3);
    }

    public function encryptSecret(string $base32Secret): string
    {
        // Validate before encrypting to avoid persisting malformed enrollment data.
        $this->base32Decode($base32Secret);
        return Crypt::encryptString($base32Secret);
    }

    private function base32Encode(string $binary): string
    {
        $bits = '';
        foreach (str_split($binary) as $byte) {
            $bits .= str_pad(decbin(ord($byte)), 8, '0', STR_PAD_LEFT);
        }

        $encoded = '';
        foreach (str_split($bits, 5) as $chunk) {
            $encoded .= self::BASE32_ALPHABET[bindec(str_pad($chunk, 5, '0'))];
        }

        return $encoded;
    }

    private function base32Decode(string $encoded): string
    {
        $normalized = strtoupper(rtrim($encoded, '='));
        if ($normalized === '' || !preg_match('/^[A-Z2-7]+$/', $normalized)) {
            throw new InvalidArgumentException('The TOTP secret is not valid Base32.');
        }

        $bits = '';
        foreach (str_split($normalized) as $character) {
            $position = strpos(self::BASE32_ALPHABET, $character);
            if ($position === false) {
                throw new InvalidArgumentException('The TOTP secret is not valid Base32.');
            }
            $bits .= str_pad(decbin($position), 5, '0', STR_PAD_LEFT);
        }

        $binary = '';
        foreach (str_split($bits, 8) as $byte) {
            if (strlen($byte) === 8) {
                $binary .= chr(bindec($byte));
            }
        }

        if (strlen($binary) < 10) {
            throw new InvalidArgumentException('The TOTP secret is too short.');
        }

        return $binary;
    }
}
