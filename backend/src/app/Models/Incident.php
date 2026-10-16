<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Incident extends Model
{
    protected $fillable = ['requester_id', 'type', 'description', 'latitude', 'longitude', 'accuracy_m', 'status', 'assigned_to'];
    protected function casts(): array { return ['latitude' => 'float', 'longitude' => 'float', 'accuracy_m' => 'float']; }
    public function requester() { return $this->belongsTo(User::class, 'requester_id'); }
    public function assignedUnit() { return $this->belongsTo(User::class, 'assigned_to'); }
    public function statusLogs() { return $this->hasMany(IncidentStatusLog::class); }
}
