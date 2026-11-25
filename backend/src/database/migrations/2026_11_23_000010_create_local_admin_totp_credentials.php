<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('local_admin_totp_credentials', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->unique()->constrained('users')->cascadeOnDelete();
            // Laravel-encrypted TOTP secret; never store the Base32 secret in plaintext.
            $table->text('secret_ciphertext');
            $table->timestampTz('confirmed_at')->nullable();
            $table->timestampTz('expires_at')->nullable();
            $table->unsignedBigInteger('last_used_step')->nullable();
            $table->string('last_used_code_hash', 64)->nullable();
            $table->timestampsTz();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('local_admin_totp_credentials');
    }
};
