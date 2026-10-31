#!/usr/bin/env python3
"""Source-contract checks for the full-screen GPS map's offline status banner.

These checks verify UI wiring and lifecycle cleanup, not live network behavior on a phone.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()

checks = {
    "map detects validated internet instead of merely an active network": all(
        text in source for text in (
            "private fun ConnectivityManager.hasValidatedInternet()",
            "activeNetwork ?: return false",
            "NetworkCapabilities.NET_CAPABILITY_INTERNET",
            "NetworkCapabilities.NET_CAPABILITY_VALIDATED",
        )
    ),
    "map observes connectivity changes while visible": all(
        text in source for text in (
            "ConnectivityManager.NetworkCallback()",
            "override fun onAvailable(network: Network)",
            "override fun onLost(network: Network)",
            "override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities)",
            "registerDefaultNetworkCallback(callback)",
        )
    ),
    "connectivity callback is unregistered when map leaves composition": all(
        text in source for text in (
            "DisposableEffect(connectivityManager)",
            "unregisterNetworkCallback(callback)",
        )
    ),
    "network state updates are dispatched to the main thread": all(
        text in source for text in (
            "Looper.myLooper() == Looper.getMainLooper()",
            "Handler(Looper.getMainLooper()).post { hasValidatedInternet = connected }",
        )
    ),
    "compact offline toolbar status preserves the map viewport": all(
        text in source for text in (
            'if (hasValidatedInternet == false)',
            '"Offline"',
            'Internet unavailable. Map tiles may not load; incident details remain available.',
            'MaterialTheme.colorScheme.errorContainer',
            'MaterialTheme.colorScheme.onErrorContainer',
            'Modifier.size(48.dp)',
        )
    ),    "Android manifest retains network-state permission": (
        "android.permission.ACCESS_NETWORK_STATE" in manifest
    ),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} GPS map network-status contract check(s) failed")
print(f"GPS map network-status source contract OK: {len(checks)} checks.")
