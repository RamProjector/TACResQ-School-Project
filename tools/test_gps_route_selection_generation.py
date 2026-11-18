#!/usr/bin/env python3
"""Guard against publishing route results after the selected incident changes."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")
start = source.index("requestRoute = { incident ->")
end = source.index("fun canStartLiveNavigation", start)
request = source[start:end]

assert "fun isCurrentRequest(): Boolean" in request
assert "selectionGeneration == routeSelectionGeneration" in request
assert request.count("if (!isCurrentRequest()) {") >= 4, (
    "Route work must recheck request and selection generations after suspensions and before publishing results."
)
provider_call = request.index("val result = provider.route(")
late_result_guard = request.index("if (!isCurrentRequest()) {", provider_call)
route_parse = request.index("val route = result.getOrNull()", late_result_guard)
assert late_result_guard < route_parse, "A late provider response must be rejected before route parsing."
publish_guard = request.index("if (!isCurrentRequest()) {", route_parse)
publish_result = request.index("routeResult = route", publish_guard)
assert publish_guard < publish_result, "Selection generation must be checked immediately before publishing the route."

print("GPS route selection-generation contract passed.")
