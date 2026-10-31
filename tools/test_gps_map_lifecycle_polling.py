#!/usr/bin/env python3
"""Contract tests for lifecycle-aware GPS map background work."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt"


class GpsMapLifecyclePollingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")

    def test_uses_repeat_on_started_lifecycle(self):
        self.assertIn("import androidx.lifecycle.repeatOnLifecycle", self.source)
        self.assertGreaterEqual(
            self.source.count("lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)"),
            2,
            "Freshness ticker and dispatcher polling must both pause outside STARTED",
        )

    def test_dispatcher_refreshes_immediately_and_then_periodically(self):
        match = re.search(
            r"LaunchedEffect\(lifecycleOwner, role, user\.id\)\s*\{(?P<body>.*?)\n    \}",
            self.source,
            re.S,
        )
        self.assertIsNotNone(match, "Dispatcher polling effect not found")
        body = match.group("body")
        self.assertIn("if (role == Role.DISPATCHER)", body)
        self.assertIn("repeatOnLifecycle(Lifecycle.State.STARTED)", body)
        self.assertIn("refreshResponderLocations()", body)
        self.assertIn("delay(15_000)", body)
        self.assertLess(
            body.index("refreshResponderLocations()"),
            body.index("delay(15_000)"),
            "Refresh should happen immediately on entering STARTED",
        )

    def test_latest_refresh_callback_is_used_without_restarting_effect(self):
        self.assertIn(
            "val refreshResponderLocations by rememberUpdatedState(onRefreshResponderLocations)",
            self.source,
        )
        self.assertNotIn("onRefreshResponderLocations()", self.source)

    def test_map_view_forwards_the_complete_maplibre_lifecycle(self):
        for required in (
            "Lifecycle.Event.ON_START",
            "Lifecycle.Event.ON_RESUME",
            "Lifecycle.Event.ON_PAUSE",
            "Lifecycle.Event.ON_STOP",
            "mapView.onStart()",
            "mapView.onResume()",
            "mapView.onPause()",
            "mapView.onStop()",
            "mapView.onDestroy()",
        ):
            self.assertIn(required, self.source)


if __name__ == "__main__":
    unittest.main()
