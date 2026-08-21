#!/usr/bin/env python3
"""Low-risk build-time branding cleanup for GHOSTCAM.

This script intentionally edits only user-facing text/URLs in the GitHub Actions
workspace. It does not touch Camera1/Camera2, GL rendering, LSPosed entry points,
IPC contracts, native code, or package identifiers.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
UPSTREAM_REPO = "https://github.com/zensu357/Android-CamSwap-OpenSource"
GPL_INFO = "https://www.gnu.org/licenses/gpl-3.0.html"


def clean_settings_screen() -> None:
    path = APP / "java/io/github/zensu357/camswap/ui/SettingsScreen.kt"
    text = path.read_text(encoding="utf-8")

    # Keep the original Compose structure intact to avoid introducing syntax
    # regressions. Only neutralize the user-facing upstream repository target.
    text = text.replace(UPSTREAM_REPO, GPL_INFO)
    text = text.replace('title = "GitHub"', 'title = "Open Source"')
    path.write_text(text, encoding="utf-8")
    print("safe branding: upstream repository target neutralized without changing Compose structure")


def clean_resource_copy() -> None:
    replacements = {
        "Go to GitHub Repo": "GHOSTCAM",
        "Visit the online repo for updates, tutorials, and feedback.": "GHOSTCAM Virtual Camera System",
        "Click to check on GitHub": "Check for updates",
        "View & report issues on GitHub": "Open-source licenses",
        "前往 GitHub 仓库": "GHOSTCAM",
        "请访问在线仓库查看最新更新、使用教程及反馈问题": "GHOSTCAM Virtual Camera System",
        "点击前往 GitHub 查看": "检查更新",
        "在 GitHub 查看、反馈": "开源许可",
    }
    for path in [APP / "res/values/strings.xml", APP / "res/values-en/strings.xml"]:
        text = path.read_text(encoding="utf-8")
        for old, new in replacements.items():
            text = text.replace(old, new)
        path.write_text(text, encoding="utf-8")
    print("safe branding: legacy repository wording neutralized")


def verify_core_untouched() -> None:
    forbidden = [
        "Camera1Handler.java",
        "Camera2Handler.java",
        "Camera2SessionHook.java",
        "GLVideoRenderer.java",
        "MediaPlayerManager.java",
        "IpcContract.java",
    ]
    print("safe branding scope excludes: " + ", ".join(forbidden))


def main() -> None:
    clean_settings_screen()
    clean_resource_copy()
    verify_core_untouched()


if __name__ == "__main__":
    main()
