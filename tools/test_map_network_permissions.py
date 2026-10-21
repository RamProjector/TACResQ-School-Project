from pathlib import Path

manifest = Path("app/src/main/AndroidManifest.xml").read_text()
assert 'android.permission.INTERNET' in manifest, "map tile downloads require internet permission"
assert 'android.permission.ACCESS_NETWORK_STATE' in manifest, (
    "osmdroid network availability checks require ACCESS_NETWORK_STATE"
)
print("Android map network permissions contract passed.")
