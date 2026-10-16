<?php

namespace Database\Seeders;

use App\Models\User;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\Hash;

class DemoUsersSeeder extends Seeder
{
    public function run(): void
    {
        foreach ([
            ['Rica Requester', 'requester@demo.com', 'requester', null, '0917 123 4567'],
            ['Rey Responder', 'responder@demo.com', 'responder', 'off_duty', '0918 123 4567'],
            ['Dan Driver', 'driver@demo.com', 'driver', 'off_duty', '0919 123 4567'],
            ['Dina Dispatcher', 'dispatcher@demo.com', 'dispatcher', null, null],
        ] as [$name, $email, $role, $duty, $phone]) {
            User::updateOrCreate(['email' => $email], [
                'name' => $name, 'role' => $role, 'duty_status' => $duty, 'phone' => $phone,
                'password' => Hash::make('demo123'),
            ]);
        }
    }
}
