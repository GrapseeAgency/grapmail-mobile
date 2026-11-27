#!/usr/bin/env python3
"""
High-resolution marketing screenshot capture & verification pipeline for GrapMail.

Captures lossless native PNG screenshots across:
  - phone   (1440 x 3120, light & dark)
  - compact (1080 x 2340, light & dark)
  - tablet  (1600 x 2560, light & dark)
  - interaction-states (1440 x 3120)

Outputs under marketing-captures/:
  phone/{light,dark}/
  compact/{light,dark}/
  tablet/{light,dark}/
  interaction-states/
  diagnostics/
  contact-sheets/
  manifest.json
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import struct
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT_ROOT = ROOT / "marketing-captures"
PKG = "com.grapsee.grapmail"
ACTIVITY = f"{PKG}/.MarketingCaptureActivity"
APK_DEFAULT = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"

SDK = Path(os.environ.get("ANDROID_HOME") or "/mnt/new_volume/asdk-smoke")
ADB = str(SDK / "platform-tools/adb")
if not Path(ADB).is_file():
    ADB = "/mnt/new_volume/asdk-smoke/platform-tools/adb"


@dataclass(frozen=True)
class SceneSpec:
    filename: str
    scene: str
    marker: str
    interaction: Optional[str] = None
    require_ime: bool = False
    settle_sec: float = 1.6


PHONE_SCENES: list[SceneSpec] = [
    SceneSpec("01-welcome", "welcome", "GrapMail", settle_sec=4.2),
    SceneSpec("02-onboarding-sanctuary", "onboarding-1", "Welcome to your sanctuary", settle_sec=4.5),
    SceneSpec("03-onboarding-screener", "onboarding-2", "You decide who reaches you", settle_sec=4.5),
    SceneSpec("04-inbox-populated", "inbox", "Marcus Chen"),
    SceneSpec("05-navigation-drawer", "inbox", "mail-drawer-Help", interaction="open_drawer"),
    SceneSpec("06-search-results", "search", "Brand refresh", interaction="focus_search", require_ime=True),
    SceneSpec("07-threaded-conversation", "thread", "Brand refresh"),
    SceneSpec("08-rich-email-reader", "thread-rich", "mail-show-images", settle_sec=2.2),
    SceneSpec("09-encrypted-email", "thread-encrypted", "Hardware key recovery ceremony"),
    SceneSpec("10-compose", "compose", "marcus@atelier-studio.example"),
    SceneSpec("11-compose-keyboard", "compose-keyboard", "mail-body-field", interaction="focus_compose_body", require_ime=True),
    SceneSpec("12-labels", "labels", "Design"),
    SceneSpec("13-files", "files", "grapmail-brand-spec-v4.pdf"),
    SceneSpec("14-attachment-preview", "attachment-preview", "grapmail-brand-spec-v4.pdf"),
    SceneSpec("15-encryption-hub", "e2e-status", "Your key is unlocked for this session"),
    SceneSpec("16-security-center", "security-center", "Samsung Galaxy S24 Ultra"),
    SceneSpec("17-sender-screening", "screener", "Waiting in the queue"),
    SceneSpec("18-subscriptions", "subscriptions", "10 or fewer emails recently"),
    SceneSpec("19-connected-accounts", "connected-accounts", "elena.vance@outlook.example"),
]

COMPACT_SCENES: list[SceneSpec] = [
    SceneSpec("01-welcome", "welcome", "GrapMail", settle_sec=2.2),
    SceneSpec("04-inbox-populated", "inbox", "Marcus Chen"),
    SceneSpec("07-threaded-conversation", "thread", "Brand refresh"),
    SceneSpec("10-compose", "compose", "marcus@atelier-studio.example"),
    SceneSpec("16-security-center", "security-center", "Samsung Galaxy S24 Ultra"),
]

TABLET_SCENES: list[SceneSpec] = [
    SceneSpec("01-welcome", "welcome", "GrapMail", settle_sec=2.2),
    SceneSpec("04-inbox-two-pane", "tablet-inbox-thread", "Brand refresh"),
    SceneSpec("08-rich-email-reader", "thread-rich", "mail-show-images", settle_sec=2.2),
    SceneSpec("10-compose", "compose", "marcus@atelier-studio.example"),
    SceneSpec("16-security-center", "security-center", "Samsung Galaxy S24 Ultra"),
]

INTERACTION_SCENES: list[tuple[str, SceneSpec]] = [
    ("light", SceneSpec("01-account-switcher-popup-light", "inbox", "Add another account", interaction="open_switcher")),
    ("dark", SceneSpec("01-account-switcher-popup-dark", "inbox", "Add another account", interaction="open_switcher")),
    ("light", SceneSpec("02-selection-toolbar-light", "selection", "Archive")),
    ("dark", SceneSpec("02-selection-toolbar-dark", "selection", "Archive")),
    ("light", SceneSpec("03-swipe-archive-light", "inbox", "Marcus Chen", interaction="swipe_archive")),
    ("light", SceneSpec("04-sheet-sort-filter-light", "sheet-sort", "gm-sheets-sort")),
    ("dark", SceneSpec("04-sheet-sort-filter-dark", "sheet-sort", "gm-sheets-sort")),
    ("light", SceneSpec("05-sheet-assign-labels-light", "sheet-label", "gm-sheets-label")),
    ("dark", SceneSpec("05-sheet-assign-labels-dark", "sheet-label", "gm-sheets-label")),
    ("light", SceneSpec("06-sheet-snooze-light", "sheet-snooze", "gm-sheets-snooze")),
    ("light", SceneSpec("07-sheet-move-light", "sheet-move", "gm-sheets-move")),
    ("light", SceneSpec("08-sheet-sender-decision-light", "sheet-sender", "gm-sheets-sender")),
    ("light", SceneSpec("09-sheet-unsubscribe-light", "sheet-unsub", "gm-sheets-unsub")),
    ("light", SceneSpec("10-undo-banner-light", "undo", "Archived 2 conversations")),
    ("dark", SceneSpec("11-reader-actions-menu-dark", "reader-actions", "Block sender")),
    ("light", SceneSpec("12-e2e-recipients-lookup-light", "e2e-recipients", "marcus@atelier-studio.example")),
]

EXPECTED_RESOLUTIONS = {
    "phone": (1440, 3120),
    "compact": (1080, 2340),
    "tablet": (1600, 2560),
}


def run(cmd: list[str], check: bool = False, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, capture_output=True, timeout=timeout, check=check)


def adb(*args: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return run([ADB, *args], timeout=timeout)


def adb_shell(cmd: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return run([ADB, "shell", cmd], timeout=timeout)


def setup_device_consistency() -> None:
    adb_shell("settings put global hide_error_dialogs 1")
    for s in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb_shell(f"settings put global {s} 0")
    adb_shell("settings put system font_scale 1.0")
    adb_shell("settings put global sysui_demo_allowed 1")
    adb_shell("am broadcast -a com.android.systemui.demo -e command enter")
    adb_shell("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941")
    adb_shell("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false")
    adb_shell("am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype 5g -e level 4")
    adb_shell("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")
    adb_shell("cmd statusbar collapse")


def set_appearance(dark: bool) -> None:
    mode = "yes" if dark else "no"
    adb_shell(f"cmd uimode night {mode}")
    time.sleep(1.5)


def dump_ui(dest_xml: Path) -> str:
    for _ in range(3):
        try:
            adb_shell("uiautomator dump /sdcard/gm_ui.xml", timeout=10)
            res = adb("pull", "/sdcard/gm_ui.xml", str(dest_xml), timeout=10)
            if res.returncode == 0 and dest_xml.exists() and dest_xml.stat().st_size > 100:
                return dest_xml.read_text(encoding="utf-8", errors="ignore")
        except subprocess.TimeoutExpired:
            pass
        time.sleep(0.8)
    return ""


def parse_nodes(xml_text: str) -> list[dict[str, str]]:
    nodes = []
    for m in re.finditer(r"<node[^>]*/?>", xml_text):
        attrs = {k: html.unescape(v) for k, v in re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0))}
        nodes.append(attrs)
    return nodes


def center_of(bounds: str) -> Optional[tuple[int, int]]:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_node_center(xml_text: str, pred: Callable[[dict[str, str]], bool]) -> Optional[tuple[int, int]]:
    for n in parse_nodes(xml_text):
        if pred(n):
            c = center_of(n.get("bounds", ""))
            if c:
                return c
    return None


def foreground_is_app() -> bool:
    try:
        r1 = adb_shell("dumpsys activity activities | grep -iE 'topResumedActivity|mResumedActivity|ResumedActivity:' | head -3", timeout=10)
        r2 = adb_shell("dumpsys window | grep -iE 'mCurrentFocus|mFocusedApp' | head -3", timeout=10)
        combined = (r1.stdout or "") + "\n" + (r2.stdout or "")
        if "Splash Screen" in combined:
            return False
        return PKG in combined
    except subprocess.TimeoutExpired:
        return False


def keyboard_shown() -> bool:
    r = adb_shell("dumpsys input_method")
    return "mInputShown=true" in (r.stdout or "")


def verify_png(path: Path, expected_size: tuple[int, int]) -> tuple[bool, str, tuple[int, int], str]:
    if not path.exists() or path.stat().st_size < 20_000:
        return False, "file missing or too small", (0, 0), ""
    raw = path.read_bytes()
    if raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
        return False, "invalid PNG header", (0, 0), ""
    w, h = struct.unpack(">II", raw[16:24])
    if (w, h) != expected_size:
        return False, f"unexpected dimensions {w}x{h} != {expected_size[0]}x{expected_size[1]}", (w, h), ""
    with Image.open(path) as im:
        rgb = im.convert("RGB")
        extrema = rgb.getextrema()
        if all(lo == hi for lo, hi in extrema):
            return False, "solid blank image", (w, h), ""
    sha = hashlib.sha256(raw).hexdigest()
    return True, "ok", (w, h), sha


def perform_interaction(spec: SceneSpec, xml_text: str, expected_size: tuple[int, int], out_png: Path) -> Optional[str]:
    act = spec.interaction
    if act == "open_drawer":
        pos = find_node_center(xml_text, lambda n: n.get("resource-id", "").rsplit("/", 1)[-1] == "mail-menu")
        if not pos:
            return "mail-menu not found"
        adb_shell(f"input tap {pos[0]} {pos[1]}")
        time.sleep(1.2)
    elif act == "focus_search":
        pos = find_node_center(xml_text, lambda n: n.get("resource-id", "").rsplit("/", 1)[-1] == "mail-search")
        if not pos:
            return "mail-search not found"
        adb_shell(f"input tap {pos[0]} {pos[1]}")
        time.sleep(1.5)
    elif act == "focus_compose_body":
        pos = find_node_center(xml_text, lambda n: n.get("resource-id", "").rsplit("/", 1)[-1] == "mail-body-field")
        if not pos:
            return "mail-body-field not found"
        adb_shell(f"input tap {pos[0]} {pos[1]}")
        time.sleep(1.5)
    elif act == "open_switcher":
        pos = find_node_center(xml_text, lambda n: n.get("content-desc", "") in ("Account", "Switch account") or "account" in n.get("content-desc", "").lower())
        if not pos:
            # Fallback: top-right avatar beside mail-search
            w, _ = expected_size
            pos = (w - 84, 156)
        adb_shell(f"input tap {pos[0]} {pos[1]}")
        time.sleep(1.4)
    elif act == "swipe_archive":
        # Find first message row ("Marcus Chen") and hold a slow swipe mid-gesture while capturing
        pos = find_node_center(xml_text, lambda n: n.get("text", "") == "Marcus Chen")
        if not pos:
            return "Marcus Chen row not found for swipe"
        w, _ = expected_size
        y = pos[1]
        x1 = int(w * 0.14)
        x2 = int(w * 0.62)
        proc = subprocess.Popen([ADB, "shell", f"input swipe {x1} {y} {x2} {y} 2600"])
        time.sleep(1.5)
        with out_png.open("wb") as f:
            subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=True)
        proc.wait(timeout=10)
        return "CAPTURED_INLINE"
    return None


def capture_scene(
    device: str,
    appearance: str,
    spec: SceneSpec,
    dest_dir: Path,
    diag_dir: Path,
    expected_size: tuple[int, int],
) -> dict:
    dest_dir.mkdir(parents=True, exist_ok=True)
    diag_dir.mkdir(parents=True, exist_ok=True)
    out_png = dest_dir / f"{spec.filename}.png"
    tmp_xml = diag_dir / f".{device}-{appearance}-{spec.filename}.xml"

    adb_shell(f"am force-stop {PKG}", timeout=5)
    adb_shell(f"am start -W -n {ACTIVITY} --es scene {spec.scene}", timeout=10)
    time.sleep(spec.settle_sec)

    xml_text = ""
    if spec.interaction:
        # First wait for base scene to compose
        for _ in range(10):
            xml_text = dump_ui(tmp_xml)
            if f"gm-marketing:{spec.scene}" in xml_text:
                break
            time.sleep(0.8)
        inter_res = perform_interaction(spec, xml_text, expected_size, out_png)
        if inter_res == "CAPTURED_INLINE":
            ok, reason, dims, sha = verify_png(out_png, expected_size)
            tmp_xml.unlink(missing_ok=True)
            return {
                "scene": spec.filename,
                "route_scene": spec.scene,
                "device": device,
                "appearance": appearance,
                "file": str(out_png.relative_to(OUT_ROOT)),
                "resolution": f"{dims[0]}x{dims[1]}",
                "verified": ok,
                "note": reason,
                "bytes": out_png.stat().st_size if out_png.exists() else 0,
                "sha256": sha,
            }
        elif inter_res is not None:
            return record_diagnostic(device, appearance, spec, out_png, diag_dir, tmp_xml, f"interaction failed: {inter_res}")

    # Wait for settled marker
    settled = False
    is_webview_anim = spec.scene.startswith("onboarding-")
    if is_webview_anim:
        # Onboarding hosts a continuous WebView animation which starves uiautomator's idle wait.
        for _ in range(8):
            if foreground_is_app():
                settled = True
                break
            time.sleep(0.8)
    else:
        for attempt in range(10):
            xml_text = dump_ui(tmp_xml)
            if "isn't responding" in xml_text:
                return record_diagnostic(device, appearance, spec, out_png, diag_dir, tmp_xml, "ANR dialog in view")
            has_host = f"gm-marketing:{spec.scene}" in xml_text
            has_marker = spec.marker in xml_text
            ime_ok = (not spec.require_ime) or keyboard_shown()
            if has_host and has_marker and ime_ok:
                settled = True
                break
            time.sleep(0.9)

    if not settled or not foreground_is_app():
        return record_diagnostic(
            device, appearance, spec, out_png, diag_dir, tmp_xml,
            f"marker '{spec.marker}' not settled or app not foreground (ime_ok={keyboard_shown() if spec.require_ime else True})",
        )

    time.sleep(1.5)
    captured = False
    for cap_att in range(3):
        try:
            with out_png.open("wb") as f:
                subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=True, timeout=15)
            if out_png.exists() and out_png.stat().st_size > 5000:
                captured = True
                break
        except Exception as e:
            print(f"screencap attempt {cap_att+1} failed: {e}", file=sys.stderr)
            time.sleep(2)

    if not captured:
        return record_diagnostic(device, appearance, spec, out_png, diag_dir, tmp_xml, "screencap failed")
    tmp_xml.unlink(missing_ok=True)

    ok, reason, dims, sha = verify_png(out_png, expected_size)
    if not ok:
        diag_png = diag_dir / f"{device}-{appearance}-{spec.filename}-bad.png"
        if out_png.exists():
            out_png.rename(diag_png)
        return {
            "scene": spec.filename,
            "route_scene": spec.scene,
            "device": device,
            "appearance": appearance,
            "file": None,
            "resolution": f"{dims[0]}x{dims[1]}",
            "verified": False,
            "note": reason,
        }
    print(f"  [OK] {device}/{appearance}/{spec.filename}.png ({dims[0]}x{dims[1]}, {out_png.stat().st_size // 1024} KB)")
    return {
        "scene": spec.filename,
        "route_scene": spec.scene,
        "device": device,
        "appearance": appearance,
        "file": str(out_png.relative_to(OUT_ROOT)),
        "resolution": f"{dims[0]}x{dims[1]}",
        "verified": True,
        "note": "ok",
        "bytes": out_png.stat().st_size,
        "sha256": sha,
    }


def record_diagnostic(
    device: str, appearance: str, spec: SceneSpec, out_png: Path, diag_dir: Path, tmp_xml: Path, reason: str,
) -> dict:
    prefix = f"{device}-{appearance}-{spec.filename}"
    diag_png = diag_dir / f"{prefix}.png"
    with diag_png.open("wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=False)
    if tmp_xml.exists():
        tmp_xml.rename(diag_dir / f"{prefix}.xml")
    log_txt = diag_dir / f"{prefix}-logcat.txt"
    r = adb("logcat", "-d", "-t", "120")
    log_txt.write_text(r.stdout or "", encoding="utf-8", errors="ignore")
    if out_png.exists():
        out_png.unlink()
    print(f"  [FAIL] {prefix}: {reason}", file=sys.stderr)
    return {
        "scene": spec.filename,
        "route_scene": spec.scene,
        "device": device,
        "appearance": appearance,
        "file": None,
        "resolution": None,
        "verified": False,
        "note": reason,
    }


def make_contact_sheet(images: list[tuple[str, Path]], out_path: Path, cols: int = 4) -> None:
    if not images:
        return
    thumb_w, thumb_h = 360, 680
    pad, label_h = 24, 40
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new("RGB", (pad + cols * (thumb_w + pad), pad + rows * (thumb_h + label_h + pad)), (18, 20, 26))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for idx, (label, path) in enumerate(images):
        r, c = divmod(idx, cols)
        x = pad + c * (thumb_w + pad)
        y = pad + r * (thumb_h + label_h + pad)
        with Image.open(path) as im:
            im_rgb = im.convert("RGB")
            im_rgb.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
            dx = x + (thumb_w - im_rgb.width) // 2
            dy = y + (thumb_h - im_rgb.height) // 2
            sheet.paste(im_rgb, (dx, dy))
        draw.text((x + 4, y + thumb_h + 10), label[:42], fill=(228, 232, 240), font=font)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path, format="PNG")


def finalize_deliverables() -> None:
    git_commit = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    entries = []
    seen_hashes: dict[tuple[str, str], dict[str, str]] = {}

    for dev in ("phone", "compact", "tablet"):
        expected = EXPECTED_RESOLUTIONS[dev]
        for app in ("light", "dark"):
            folder = OUT_ROOT / dev / app
            if not folder.exists():
                continue
            for png in sorted(folder.glob("*.png")):
                ok, reason, dims, sha = verify_png(png, expected)
                dup_of = seen_hashes.setdefault((dev, app), {}).get(sha)
                if not dup_of and sha:
                    seen_hashes[(dev, app)][sha] = png.name
                entries.append({
                    "scene": png.stem,
                    "category": dev,
                    "device": dev,
                    "appearance": app,
                    "file": str(png.relative_to(OUT_ROOT)),
                    "resolution": f"{dims[0]}x{dims[1]}",
                    "git_commit": git_commit,
                    "verified": ok and dup_of is None,
                    "duplicate_of": dup_of,
                    "note": reason if dup_of is None else f"duplicate of {dup_of}",
                    "bytes": png.stat().st_size,
                    "sha256": sha,
                })

    inter_dir = OUT_ROOT / "interaction-states"
    if inter_dir.exists():
        for png in sorted(inter_dir.glob("*.png")):
            ok, reason, dims, sha = verify_png(png, EXPECTED_RESOLUTIONS["phone"])
            app = "dark" if png.stem.endswith("-dark") else "light"
            entries.append({
                "scene": png.stem,
                "category": "interaction-states",
                "device": "phone",
                "appearance": app,
                "file": str(png.relative_to(OUT_ROOT)),
                "resolution": f"{dims[0]}x{dims[1]}",
                "git_commit": git_commit,
                "verified": ok,
                "duplicate_of": None,
                "note": reason,
                "bytes": png.stat().st_size,
                "sha256": sha,
            })

    manifest = {
        "git_commit": git_commit,
        "total_verified": sum(1 for e in entries if e["verified"]),
        "total_entries": len(entries),
        "captures": entries,
    }
    (OUT_ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    cs_dir = OUT_ROOT / "contact-sheets"
    for dev in ("phone", "compact", "tablet"):
        for app in ("light", "dark"):
            imgs = [(e["scene"], OUT_ROOT / e["file"]) for e in entries if e["verified"] and e["category"] == dev and e["appearance"] == app]
            make_contact_sheet(imgs, cs_dir / f"{dev}-{app}.png", cols=5 if dev == "phone" else 3)
    inter_imgs = [(e["scene"], OUT_ROOT / e["file"]) for e in entries if e["verified"] and e["category"] == "interaction-states"]
    make_contact_sheet(inter_imgs, cs_dir / "interaction-states.png", cols=4)
    print(f"Manifest written: {manifest['total_verified']}/{manifest['total_entries']} verified captures.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", choices=["phone", "compact", "tablet"])
    parser.add_argument("--interactions", action="store_true")
    parser.add_argument("--finalize", action="store_true")
    parser.add_argument("--skip-existing", action="store_true")
    parser.add_argument("--apk", default=str(APK_DEFAULT))
    args = parser.parse_args()

    if args.finalize:
        finalize_deliverables()
        return 0

    if not args.device:
        parser.error("--device is required unless --finalize is used")

    apk_path = Path(args.apk)
    if not apk_path.is_file():
        print(f"APK not found: {apk_path}", file=sys.stderr)
        return 1

    adb("wait-for-device")
    setup_device_consistency()
    res = adb("install", "-r", str(apk_path), timeout=90)
    if res.returncode != 0:
        print(f"adb install failed:\n{res.stdout}\n{res.stderr}", file=sys.stderr)
        return 1

    expected = EXPECTED_RESOLUTIONS[args.device]
    diag_dir = OUT_ROOT / "diagnostics"
    scenes = {
        "phone": PHONE_SCENES,
        "compact": COMPACT_SCENES,
        "tablet": TABLET_SCENES,
    }[args.device]

    for appearance in ("light", "dark"):
        set_appearance(dark=(appearance == "dark"))
        for spec in scenes:
            # Onboarding scenes 02 and 03 have fixed light-background vector artwork and run
            # live WebView Chromium animations that lock swiftshader when forced into system night mode.
            if appearance == "dark" and spec.filename in ("02-onboarding-sanctuary", "03-onboarding-screener"):
                continue
            out_file = OUT_ROOT / args.device / appearance / f"{spec.filename}.png"
            if args.skip_existing and verify_png(out_file, expected)[0]:
                continue
            try:
                capture_scene(args.device, appearance, spec, OUT_ROOT / args.device / appearance, diag_dir, expected)
            except Exception as e:
                print(f"Exception capturing {spec.filename}: {e}", file=sys.stderr)

    if args.interactions and args.device == "phone":
        current_mode = None
        for appearance, spec in INTERACTION_SCENES:
            out_file = OUT_ROOT / "interaction-states" / f"{spec.filename}.png"
            if args.skip_existing and verify_png(out_file, expected)[0]:
                continue
            if appearance != current_mode:
                set_appearance(dark=(appearance == "dark"))
                current_mode = appearance
            try:
                capture_scene("phone", appearance, spec, OUT_ROOT / "interaction-states", diag_dir, expected)
            except Exception as e:
                print(f"Exception capturing interaction {spec.filename}: {e}", file=sys.stderr)

    set_appearance(dark=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
