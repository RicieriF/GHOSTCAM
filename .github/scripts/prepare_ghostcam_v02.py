#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"


def patch_gate():
    path = APP / "java/io/github/zensu357/camswap/GhostCamLicenseGate.kt"
    text = path.read_text(encoding="utf-8")
    if "GhostCamEntitledShell { content() }" not in text:
        old = """    if (entitled) {\n        content()\n        return\n    }"""
        new = """    if (entitled) {\n        GhostCamEntitledShell { content() }\n        return\n    }"""
        if old not in text:
            raise RuntimeError("Entitlement content block not found")
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("✓ quick editor shell")


def patch_renderer():
    renderers = list(APP.rglob("GLVideoRenderer.java"))
    if len(renderers) != 1:
        raise RuntimeError(f"Expected one GLVideoRenderer.java, got {renderers}")
    path = renderers[0]
    text = path.read_text(encoding="utf-8")
    marker = "GHOSTCAM_MANUAL_TEXTURE_TRANSFORM"
    if marker not in text:
        pattern = re.compile(r"(?P<indent>^[ \t]*)(?P<receiver>[A-Za-z0-9_.$]+)\.getTransformMatrix\((?P<matrix>[A-Za-z0-9_]+)\);", re.MULTILINE)
        m = pattern.search(text)
        if not m:
            raise RuntimeError("SurfaceTexture.getTransformMatrix(...) not found")
        indent, matrix = m.group("indent"), m.group("matrix")
        injected = (
            m.group(0) + "\n"
            + indent + "// GHOSTCAM_MANUAL_TEXTURE_TRANSFORM\n"
            + indent + "float[] ghostCamAdjustedMatrix = io.github.zensu357.camswap.GhostCamRenderTransform.compose("
            + matrix + ", 0, 0, 0, 0);\n"
            + indent + "System.arraycopy(ghostCamAdjustedMatrix, 0, " + matrix + ", 0, 16);"
        )
        text = text[:m.start()] + injected + text[m.end():]

    if "GhostCamRenderTransform.backgroundRgba" not in text:
        clear = re.compile(r"(?P<indent>^[ \t]*)GLES20\.glClearColor\([^;]+\);", re.MULTILINE)
        m = clear.search(text)
        if m:
            ind = m.group("indent")
            replacement = (
                ind + "float[] ghostCamBackground = io.github.zensu357.camswap.GhostCamRenderTransform.backgroundRgba();\n"
                + ind + "GLES20.glClearColor(ghostCamBackground[0], ghostCamBackground[1], ghostCamBackground[2], ghostCamBackground[3]);"
            )
            text = text[:m.start()] + replacement + text[m.end():]

    path.write_text(text, encoding="utf-8")
    print("✓ OpenGL manual framing")


def sanitize_upstream_ui():
    # Internal Java/Kotlin package names remain unchanged for module compatibility.
    # Only user-facing URLs/names are rebranded. GPL compliance is preserved in
    # LICENSE and OPEN_SOURCE_NOTICES.md.
    replacements = {
        "https://github.com/zensu357/Android-CamSwap-OpenSource": GPL_URL,
        "http://github.com/zensu357/Android-CamSwap-OpenSource": GPL_URL,
        "https://github.com/zensu357/Android-CamSwap": GPL_URL,
        "Android-CamSwap-OpenSource": "GHOSTCAM",
    }
    changed = 0
    for path in APP.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".kt", ".java", ".xml", ".txt", ".properties"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        original = text
        for old, new in replacements.items():
            text = text.replace(old, new)

        # In resource text only, remove the old maintainer handle when it is
        # presented as branding/author text. Do not alter package declarations.
        if "/res/" in path.as_posix():
            text = re.sub(r">\s*zensu357\s*<", ">GHOSTCAM<", text, flags=re.IGNORECASE)
            text = re.sub(r"github\.com/zensu357(?:/[^<\"' ]*)?", "gnu.org/licenses/gpl-3.0.html", text, flags=re.IGNORECASE)

        if text != original:
            path.write_text(text, encoding="utf-8")
            changed += 1

    offenders = []
    for path in APP.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".kt", ".java", ".xml", ".txt", ".properties"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "https://github.com/zensu357" in text or "Android-CamSwap-OpenSource" in text:
            offenders.append(str(path.relative_to(ROOT)))
    if offenders:
        raise RuntimeError("Upstream UI reference remains in: " + ", ".join(offenders))
    print(f"✓ sanitized upstream UI references in {changed} file(s)")


def verify_required_files():
    required = [
        APP / "java/io/github/zensu357/camswap/GhostCamApi.kt",
        APP / "java/io/github/zensu357/camswap/GhostCamDeviceIdentity.kt",
        APP / "java/io/github/zensu357/camswap/GhostCamQuickPanel.kt",
        APP / "java/io/github/zensu357/camswap/GhostCamRenderTransform.java",
        APP / "java/io/github/zensu357/camswap/GhostCamTransformSettings.kt",
        APP / "res/values/ghostcam_config.xml",
    ]
    missing = [str(p.relative_to(ROOT)) for p in required if not p.exists()]
    if missing:
        raise RuntimeError("Missing GHOSTCAM source files: " + ", ".join(missing))
    print("✓ GHOSTCAM v0.2 source set")


def main():
    verify_required_files()
    sanitize_upstream_ui()
    patch_gate()
    patch_renderer()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"GHOSTCAM v0.2 preparation failed: {exc}", file=sys.stderr)
        raise
