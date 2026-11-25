<?php

namespace App\Providers;

use App\Contracts\FcmClientContract;
use App\FcmHttpV1Client;

use Illuminate\Support\ServiceProvider;
use Illuminate\Http\Resources\Json\JsonResource;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->bind(FcmClientContract::class, FcmHttpV1Client::class);
    }
    public function boot(): void { JsonResource::withoutWrapping(); }
}
