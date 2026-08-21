#!/usr/bin/env python3
"""Build-time integration guard for the current GHOSTCAM test branch.

The project is a fork with a fragile GL renderer. This script makes two small,
verifiable integrations without rewriting the Camera1/Camera2 hook stack:
  1) Wraps the unlocked app content with the GHOSTCAM quick editor.
  2) Applies the manual texture transform matrix in GLVideoRenderer.

It intentionally does not add face recognition, liveness logic, identity
matching, or anti-fraud bypass behavior.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]


def patch_gate() -> None:
    path = ROOT / "app/src/main/java/io/github/zensu357/camswap/GhostCamLicenseGate.kt"
    text = path.read_text(encoding="utf-8")
    if "GhostCamEntitledShell" in text:
        print("Gate shell already integrated")
        return
    old = """    if (entitled) {\n        content()\n        return\n    }"""
    new = """    if (entitled) {\n        GhostCamEntitledShell { content() }\n        return\n    }"""
    if old not in text:
        raise RuntimeError("Could not find entitlement content block in GhostCamLicenseGate.kt")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("Integrated GHOSTCAM quick editor shell")


def find_renderer() -> Path:
    matches = list((ROOT / "app/src/main").rglob("GLVideoRenderer.java"))
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one GLVideoRenderer.java, found {len(matches)}: {matches}")
    return matches[0]


def patch_renderer() -> None:
    path = find_renderer()
    text = path.read_text(encoding="utf-8")
    marker = "GHOSTCAM_MANUAL_TEXTURE_TRANSFORM"
    if marker in text:
        print("GL renderer transform already integrated")
        return

    # SurfaceTexture's native matrix remains the base. We post-compose only a
    # generic manual media transform (zoom, X/Y, rotation, mirror).
    pattern = re.compile(r"(?P<indent>^[ \t]*)(?P<receiver>[A-Za-z0-9_.$]+)\.getTransformMatrix\((?P<matrix>[A-Za-z0-9_]+)\);", re.MULTILINE)
    match = pattern.search(text)
    if not match:
        raise RuntimeError("Could not find SurfaceTexture.getTransformMatrix(...) in GLVideoRenderer.java")

    indent = match.group("indent")
    matrix = match.group("matrix")
    original = match.group(0)
    injected = (
        original
        + "\n"
        + indent + "// GHOSTCAM_MANUAL_TEXTURE_TRANSFORM\n"
        + indent + "float[] ghostCamAdjustedMatrix = io.github.zensu357.camswap.GhostCamRenderTransform.compose("
        + matrix + ", 0, 0, 0, 0);\n"
        + indent + "System.arraycopy(ghostCamAdjustedMatrix, 0, " + matrix + ", 0, 16);"
    )
    text = text[:match.start()] + injected + text[match.end():]

    # Respect the selected background where the renderer already clears a frame.
    if "GhostCamRenderTransform.backgroundRgba" not in text:
        clear = re.compile(r"(?P<indent>^[ \t]*)GLES20\.glClearColor\([^;]+\);", re.MULTILINE)
        cm = clear.search(text)
        if cm:
            ind = cm.group("indent")
            replacement = (
                ind + "float[] ghostCamBackground = io.github.zensu357.camswap.GhostCamRenderTransform.backgroundRgba();\n"
                + ind + "GLES20.glClearColor(ghostCamBackground[0], ghostCamBackground[1], ghostCamBackground[2], ghostCamBackground[3]);"
            )
            text = text[:cm.start()] + replacement + text[cm.end():]

    path.write_text(text, encoding="utf-8")
    print(f"Integrated manual texture transform into {path.relative_to(ROOT)}")


def main() -> int:
    patch_gate()
    patch_renderer()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"GHOSTCAM runtime patch failed: {exc}", file=sys.stderr)
        raise
