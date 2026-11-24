<?php

namespace App\Contracts;

interface FcmClientContract
{
    public function assertCredentialsAvailable(): void;

    public function send(string $projectId, string $token, array $data): array;
}
