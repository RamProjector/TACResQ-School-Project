<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class IncidentStatusLog extends Model
{
    public $timestamps = false;
    protected $fillable = ['incident_id', 'status', 'changed_by', 'created_at'];
    protected function casts(): array { return ['created_at' => 'datetime']; }
    public function incident() { return $this->belongsTo(Incident::class); }
    public function changedBy() { return $this->belongsTo(User::class, 'changed_by'); }
}
