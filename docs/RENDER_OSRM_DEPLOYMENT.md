# Render private OSRM deployment runbook

This runbook describes the optional private OSRM service for TACResQ. It is preparation only. The repository does not create the Render service, attach a disk, upload a graph, change Laravel environment variables, or enable Android route preview.

## Why Render is suitable

Render supports Docker-based private services, private networking, and persistent disks. OSRM needs all three: the pinned Docker wrapper runs the router, Laravel reaches it over the private service network, and the generated `tacresq.osrm*` graph must survive restarts and deploys.

The existing `TACResQ-School-Project` Render service is a Free web service in Singapore. Render Free services cannot use persistent disks and cannot receive private network traffic. The OSRM service therefore requires a separate paid private service and paid persistent disk. Keeping the existing Laravel service unchanged is possible, but compatibility and cost should be confirmed before provisioning.

References: [Render Docker services](https://render.com/docs/docker), [Render service types](https://render.com/docs/service-types), [Render persistent disks](https://render.com/docs/disks), and [Render free-tier limitations](https://render.com/docs/free).

## Prepared template

[`render-osrm.example.yaml`](../render-osrm.example.yaml) is intentionally not named `render.yaml`, so it cannot be accidentally treated as the repository's active Blueprint. It targets:

- service type `pserv` (private service);
- Dockerfile `backend/routing/osrm/Dockerfile` with build context `backend/routing/osrm`;
- Singapore, matching the current TACResQ Render service;
- one instance, because Render persistent disks are single-instance;
- a paid `starter` compute plan as a conservative starting point;
- a 10 GB disk mounted at `/var/data`;
- `OSRM_GRAPH_PREFIX=/var/data/tacresq`.

The disk size is a placeholder. Measure the approved operating-area graph before accepting it. Render allows increasing disk size later but not decreasing it.

## Operator-controlled provisioning sequence

1. Approve the operating area, OSM extract source, attribution, data-retention policy, graph update cadence, rollback plan, compute plan, and disk size.
2. In Render, create or apply the private service from the template values. Do not add a public URL or public route API. Keep the service in Singapore so the existing Laravel service can use Render private networking.
3. Confirm the service has one instance and its disk is mounted at `/var/data`. The OSRM wrapper intentionally exits if `/var/data/tacresq.osrm` is absent.
4. Prepare the graph outside Render using the repository's pinned workflow:

   ```sh
   cd backend/routing
   ./prepare-graph.sh /path/to/approved-area.osm.pbf /path/to/graph
   ```

   Keep the PBF and generated graph out of Git. Record the extract provider, extract date, geographic bounds, OSRM image tag, and graph size.
5. Transfer the generated `tacresq.osrm*` files into the service disk at `/var/data`. Render documents SSH/SFTP and other secure transfer options for disk-backed services. Do not put the graph in the image or source repository.
6. Restart or redeploy the service and inspect logs. Confirm startup reports a usable graph and does not print coordinate-bearing request data.
7. Run the repository's local smoke-test shape against the private service from an approved private network path. Confirm a synthetic in-area route returns valid distance, duration, and geometry. Do not test with real responder coordinates.
8. Configure the Laravel service with the Render private hostname, for example:

   ```env
   OSRM_BASE_URL=http://tacresq-osrm:5000
   ```

   Apply this only after the private service and graph have passed validation. Do not use the public `onrender.com` URL as the router base URL.
9. Run Laravel route-preview tests with synthetic accounts. Verify role, individual assignment, assignment status, rate limit, coordinate-only upstream payload, and generic error handling.
10. Enable Android route preview only in a deliberately opted-in build. Keep the external Maps fallback available and label the feature as a static preview, not live navigation.

## Updates and rollback

Prepare every replacement graph in a separate directory. Validate it before replacing the active graph. Keep the previous graph available for rollback and record the graph metadata in the deployment change log. Because a persistent disk is single-instance and prevents zero-downtime deploys, schedule graph or image changes during an approved maintenance window and verify the route smoke test immediately afterward.

Do not use a Render pre-deploy command to populate the graph: Render documents that persistent disks are not available during build or pre-deploy commands. Do not rely on the source repository, container filesystem, or a public download URL as the graph's durable storage.

## Stop conditions

Do not enable routing if the graph is missing, the service is public, Laravel cannot reach the private hostname, logs contain route coordinates, the graph's operating area is undocumented, disk/RAM capacity is unknown, or the team has not accepted the privacy and cost implications. A successful route response does not establish current road passability, traffic, closures, flooding, or emergency-vehicle access.
