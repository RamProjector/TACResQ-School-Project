# TACResQ UI screenshots

Clean mobile-viewport screenshots captured from the interactive prototype at 2× scale. Filenames group each role and the key flow states. These depict simulated/fictitious data only; none of the screens contact live dispatch or emergency services.

Browse every screen visually in the [clickable screenshot gallery](gallery.html). Select any thumbnail to open the full-size image.

**Design color guidance:** the current gallery is a high-fidelity functional prototype rather than a low-fidelity monochrome wireframe. For early IA/layout reviews, use grayscale with color reserved for meaning (not decoration); preserve semantic red for SOS/danger, and status colors only with text labels. Do not remove accessible contrast or rely on color alone.

| Screenshot | Screen / state |
|---|---|
| [`01-requester-home.png`](01-requester-home.png) | Requester home: emergency type, optional details, location readiness, and review action. |
| [`02-requester-requests-empty.png`](02-requester-requests-empty.png) | Requester request history before any report exists. |
| [`03-requester-map-empty.png`](03-requester-map-empty.png) | Requester full-screen OpenStreetMap view with no active incident; centered on the Tacloban sample area. |
| [`04-requester-safety.png`](04-requester-safety.png) | Requester privacy and safety information. |
| [`05-requester-profile.png`](05-requester-profile.png) | Requester profile and location-consent entry points. |
| [`06-requester-sign-out-confirmation.png`](06-requester-sign-out-confirmation.png) | Sign-out confirmation in the requester profile. |
| [`07-sign-in.png`](07-sign-in.png) | Role-aware sign-in preview; no real credentials are sent or stored. |
| [`08-responder-current-call-unassigned.png`](08-responder-current-call-unassigned.png) | Responder duty state before an assignment. |
| [`09-responder-history-empty.png`](09-responder-history-empty.png) | Responder history before any assignment. |
| [`10-responder-map-unassigned.png`](10-responder-map-unassigned.png) | Responder full-screen OpenStreetMap view with no assigned incident or fabricated unit position. |
| [`11-responder-safety.png`](11-responder-safety.png) | Responder privacy and safety information. |
| [`12-responder-profile.png`](12-responder-profile.png) | Responder profile and duty information. |
| [`13-dispatcher-queue-empty.png`](13-dispatcher-queue-empty.png) | Dispatcher response desk before a requester report. |
| [`14-dispatcher-field-units-empty.png`](14-dispatcher-field-units-empty.png) | Dispatcher field-unit list with duty and GPS availability separated. |
| [`15-dispatcher-map-empty.png`](15-dispatcher-map-empty.png) | Dispatcher full-screen OpenStreetMap view with no incidents and no live unit positions. |
| [`16-dispatcher-activity-empty.png`](16-dispatcher-activity-empty.png) | Dispatcher activity log before any incident events. |
| [`17-dispatcher-profile.png`](17-dispatcher-profile.png) | Dispatcher profile. |
| [`18-requester-location-consent.png`](18-requester-location-consent.png) | Location-sharing disclosure with an unchecked acknowledgement. |
| [`19-requester-sos-final-review.png`](19-requester-sos-final-review.png) | Distinct final SOS review and confirmation. |
| [`20-requester-active-request.png`](20-requester-active-request.png) | Requester incident detail with pending status, timeline, and location accuracy. |
| [`21-requester-home-active.png`](21-requester-home-active.png) | Requester home when a single active request exists. |
| [`22-requester-map-active.png`](22-requester-map-active.png) | Requester full-screen OpenStreetMap view with a sample incident pin and no live tracking. |
| [`23-requester-cancel-confirmation.png`](23-requester-cancel-confirmation.png) | Cancellation confirmation with a reason selector. |
| [`24-dispatcher-queue-pending.png`](24-dispatcher-queue-pending.png) | Pending incident in the dispatcher queue with available-unit assignment action. |
| [`25-dispatcher-assignment-confirmation.png`](25-dispatcher-assignment-confirmation.png) | Dispatcher assignment confirmation dialog. |
| [`26-dispatcher-queue-assigned.png`](26-dispatcher-queue-assigned.png) | Queue after explicit assignment; demo state only. |
| [`27-dispatcher-field-units-assigned.png`](27-dispatcher-field-units-assigned.png) | Unit list showing assignment state distinct from GPS position. |
| [`28-dispatcher-map-assigned.png`](28-dispatcher-map-assigned.png) | Dispatcher full-screen OpenStreetMap view with an incident sample point and no live unit coordinates. |
| [`29-dispatcher-activity-assigned.png`](29-dispatcher-activity-assigned.png) | Dispatcher activity log with incident creation and assignment events. |
| [`30-responder-current-call-assigned.png`](30-responder-current-call-assigned.png) | Responder active call with initial requester fix and safe-response choices. |
| [`31-responder-map-assigned.png`](31-responder-map-assigned.png) | Responder full-screen OpenStreetMap view with the assigned sample incident only; no live route or ETA. |
| [`32-responder-en-route-confirmation.png`](32-responder-en-route-confirmation.png) | Explicit confirmation before the responder marks en route. |
| [`33-responder-offline-status-queued.png`](33-responder-offline-status-queued.png) | Offline responder update marked unsynced; dispatch is not told it arrived. |
| [`34-responder-history-completed.png`](34-responder-history-completed.png) | Responder history with the completed incident and status events. |
| [`35-dispatcher-queue-resolved.png`](35-dispatcher-queue-resolved.png) | Dispatcher queue after the sample incident has been resolved. |
| [`36-dispatcher-activity-completed.png`](36-dispatcher-activity-completed.png) | Dispatcher event timeline after response completion. |
| [`37-requester-requests-resolved.png`](37-requester-requests-resolved.png) | Requester request history after the sample response is resolved. |
| [`38-requester-location-permission.png`](38-requester-location-permission.png) | Permission request explanation and non-coercive not-now option. |
| [`39-requester-stale-gps-blocked.png`](39-requester-stale-gps-blocked.png) | Stale fix is blocked and a fresh-location retry is offered. |
| [`40-requester-gps-unavailable.png`](40-requester-gps-unavailable.png) | GPS-unavailable state with retry; no guessed location is sent. |
| [`41-requester-low-accuracy-review.png`](41-requester-low-accuracy-review.png) | Low-accuracy GPS warning and explicit final SOS review. |
| [`42-requester-offline-not-sent.png`](42-requester-offline-not-sent.png) | Offline SOS is explicitly shown as not sent and awaiting user retry. |

## Re-capture

From the repository root, run `python3 tools/capture_wireframe_screenshots.py`. The script uses `/usr/bin/chromium`, Playwright, and a temporary localhost HTTP server; internet access is needed to load Leaflet and visible OpenStreetMap tiles. It does not prefetch map areas or create offline tiles.
