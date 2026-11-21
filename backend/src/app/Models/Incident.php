<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Incident extends Model
{
    protected $fillable = ['requester_id', 'type', 'description', 'latitude', 'longitude', 'accuracy_m', 'location_notes', 'location_recorded_at', 'location_source', 'status', 'assigned_to'];
    protected function casts(): array { return ['latitude' => 'float', 'longitude' => 'float', 'accuracy_m' => 'float', 'location_recorded_at' => 'immutable_datetime']; }
    public function responseTeam() { return $this->belongsToMany(User::class, 'incident_assignments')->withPivot(['assigned_by', 'assigned_at', 'responder_status', 'status_updated_at']); }
    public function requester() { return $this->belongsTo(User::class, 'requester_id'); }
    public function assignedUnit() { return $this->belongsTo(User::class, 'assigned_to'); }
    public function statusLogs() { return $this->hasMany(IncidentStatusLog::class); }
}
