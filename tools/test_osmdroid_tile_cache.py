from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
assert 'File(context.cacheDir, "osmdroid")' in source, "osmdroid base path must use app-private cache"
assert 'File(osmdroidBase, "tiles")' in source, "tile cache must be under app-private cache"
assert "setOsmdroidBasePath(osmdroidBase)" in source
assert "setOsmdroidTileCache(tileCache)" in source
assert 'osmdroidConfig.userAgentValue = "TACResQ/1.0.0 ("' in source
assert "setTileSource(TileSourceFactory.MAPNIK)" in source
print("osmdroid private-cache and app user-agent contract passed.")
