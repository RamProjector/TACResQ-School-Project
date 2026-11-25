<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Laravel\Sanctum\HasApiTokens;

class User extends Authenticatable
{
    use HasApiTokens, HasFactory;

    protected $fillable = ['name', 'email', 'phone', 'password', 'role', 'duty_status'];
    protected $hidden = ['password', 'remember_token'];
    protected function casts(): array { return ['email_verified_at' => 'datetime', 'password' => 'hashed', 'is_suspended' => 'boolean', 'suspended_at' => 'immutable_datetime']; }
    public function requestedIncidents() { return $this->hasMany(Incident::class, 'requester_id'); }
    public function assignedIncidents() { return $this->hasMany(Incident::class, 'assigned_to'); }
    public function responseIncidents() { return $this->belongsToMany(Incident::class, 'incident_assignments')->withPivot(['assigned_by', 'assigned_at', 'responder_status', 'status_updated_at']); }
}
