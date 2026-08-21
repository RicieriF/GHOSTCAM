from pathlib import Path

config_manager = Path("app/src/main/java/io/github/zensu357/camswap/ConfigManager.java")
text = config_manager.read_text(encoding="utf-8")
old = 'intent.setPackage("io.github.zensu357.camswap"); // Explicit intent to wake up host receiver'
new = 'intent.setPackage(BuildConfig.APPLICATION_ID); // Resolve the actual host package for this build variant'

if old not in text:
    raise SystemExit("Expected CamSwap host-package reference not found; refusing to patch an unknown source state")

text = text.replace(old, new, 1)
config_manager.write_text(text, encoding="utf-8")

# Guard rails: this safe compatibility fix must not touch camera or renderer code.
for protected in [
    "Camera1Handler.java",
    "Camera2Handler.java",
    "Camera2SessionHook.java",
    "GLVideoRenderer.java",
    "MediaPlayerManager.java",
]:
    path = Path("app/src/main/java/io/github/zensu357/camswap") / protected
    if not path.exists():
        raise SystemExit(f"Protected runtime file missing: {path}")

print("Applied safe runtime identity fix: config requests now target BuildConfig.APPLICATION_ID")
