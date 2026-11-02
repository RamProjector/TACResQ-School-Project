# Optional private OSRM route service

This directory contains an optional OSRM MLD server wrapper. It is not part of the existing Laravel deployment and is not provisioned by this repository. Route previews remain unavailable unless all of the following are deliberately configured: a private OSRM service with a valid regional graph, `OSRM_BASE_URL` on the Laravel service, and `-PtacresqRoutePreviewEnabled=true` when building the Android app. The Android app never calls OSRM directly.

## Privacy and access boundary

The existing authenticated Laravel `/api/route-preview` handler verifies the caller's Supabase session and own active assignment, then derives the destination from caller-authorized incident data. It sends only the fresh origin and trusted destination coordinates to OSRM. It does not send a bearer token, incident ID, description, profile data, or requester information. Coordinates are sent in OSRM's JSON POST body, not in the URL; the OSRM container also sets `DISABLE_ACCESS_LOGGING=1` to avoid logging request lines. Keep this service private, do not expose a public port, and never log request bodies or coordinates at the proxy/application layer.

The backend accepts only an internal single-label router hostname and rejects dotted domains, public IPs, loopback literals, and IPv6 literals. Configure the Laravel variable to the OSRM service's private hostname, for example `OSRM_BASE_URL=http://tacresq-osrm:5000` (use the actual internal hostname and port assigned by the host). Private routing still processes precise location data; review hosting access, retention, backups, and team membership before enabling it.

## Graph preparation

Choose and document a bounded operating-area extract before importing data. Do not add PBF files or generated graph tiles to Git. The graph must be prepared with the **car** profile and the MLD pipeline, using the same pinned OSRM image version as the server. On a capable build machine with an OSM PBF at `/data/area.osm.pbf`, the official OSRM image workflow is:

```sh
docker run --rm -t -v /data:/data ghcr.io/project-osrm/osrm-backend:v26.10.0 \
  osrm-extract -p /opt/car.lua /data/area.osm.pbf
docker run --rm -t -v /data:/data ghcr.io/project-osrm/osrm-backend:v26.10.0 \
  osrm-partition /data/area.osrm
docker run --rm -t -v /data:/data ghcr.io/project-osrm/osrm-backend:v26.10.0 \
  osrm-customize /data/area.osrm
```

Copy the generated `/data/area.osrm*` files to a persistent volume mounted at `/var/data`, named with the configured `OSRM_GRAPH_PREFIX` (default `/var/data/tacresq`). The service runs as UID 10001, so graph files must be readable by that user. Startup intentionally fails when `/var/data/tacresq.osrm` is absent. Plan graph storage, RAM, preprocessing time, update cadence, and rollback before choosing a host plan; no dataset size or capacity has been validated for the actual TACRU operating area.

## Deploy only after separate authorization

A host deployment requires a separate private service and persistent storage. No Render service, paid plan, disk, environment variable, route graph, or deployment is created by these files. Before enabling the Android build flag, verify that Laravel can reach the private hostname, the route engine health check succeeds, a synthetic in-area route returns a valid driving path, access logs contain no coordinates, and the operator accepts data-retention and service-cost implications. The service is driving-only; this initial OSRM graph does not provide a pedestrian profile.

The service is a prototype component, not a safety-certified or availability-guaranteed navigation system. Routes do not establish current passability, traffic, road closures, flood conditions, emergency-vehicle access, or the safety of a route. Keep the external Maps fallback and dispatcher confirmation flow.

## References

- [OSRM HTTP API](https://project-osrm.org/docs/v26.10.0/http) — route requests support JSON POST bodies with coordinates and options.
- [OSRM self-hosting guide](https://github.com/Project-OSRM/osrm-backend) — official Docker image and recommended MLD preprocessing steps.
- [OSRM server options](https://github.com/Project-OSRM/osrm-backend/blob/master/docs/routed.md) — `DISABLE_ACCESS_LOGGING` disables request-line output.
