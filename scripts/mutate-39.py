#!/usr/bin/env python3
"""Seed one regression per §39 rule and prove the gate refuses it.

Same contract as `mutate-38.py`: mutate a real file, run `mail-honesty.py`,
restore the file whether the run passed or failed, and require the named pin to
have failed. A seeded regression the gate accepts is a pin that holds nothing --
and §39 is the section easiest to satisfy with a comment, because every item in
it is a platform hook a screenshot cannot show.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MUTATIONS = [
    # the icon: an entry that names no file is the exact state the audit found
    ("ios/GrapMail/Assets.xcassets/AppIcon.appiconset/Contents.json",
     '"filename" : "AppIcon-1024.png",\n', ""),
    # the launch screen: back to the system's empty generated one
    ("ios/project.yml",
     "        UILaunchScreen:\n          UIImageName: LaunchBrand\n          UIColorName: LaunchBackground\n",
     "        UILaunchScreen: {}\n"),
    # the splash: installed after the window exists, which is the bug
    ("android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt",
     "        installSplashScreen()\n        super.onCreate(savedInstanceState)\n        enableEdgeToEdge()",
     "        super.onCreate(savedInstanceState)\n        enableEdgeToEdge()"),
    # the splash theme the manifest points at
    ("android/app/src/main/AndroidManifest.xml",
     'android:theme="@style/Theme.GrapMail.Splash"',
     'android:theme="@style/Theme.GrapMail"'),
    # haptics: the success tap removed from the send answer
    ("android/app/src/main/java/com/grapsee/grapmail/logic/MailboxViewModel.kt",
     "if (sent) GMHaptics.success() else GMHaptics.failure()",
     "if (sent) Unit else GMHaptics.failure()"),
    # the haptic player removed: the model still emits and nothing ever plays it
    ("android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt",
     "            hapticView.performHapticFeedback(constant)",
     "            // played by the system"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MailboxScreen.kt",
     'Text(state.staleError!!, fontSize = 12.sp, color = palette.warnInk,',
     'androidx.compose.ui.platform.LocalHapticFeedback.current.performHapticFeedback('
     'androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)\n'
     '                        Text(state.staleError!!, fontSize = 12.sp, color = palette.warnInk,'),
    # a runtime evidence frame renamed: its surface would still read as observed
    ("scripts/android-walk.sh",
     "shot 120-platform-notify-ask",
     "shot 120-platform-notification-prompt"),
    # the background read: never rescheduled
    ("ios/GrapMail/AppIntegration.swift",
     "        try? BGTaskScheduler.shared.submit(request)",
     "        _ = request"),
    # the poll inventing its own count instead of asking the server
    ("android/app/src/main/java/com/grapsee/grapmail/logic/MailPollWorker.kt",
     "val unread = stats.inbox?.unread ?: return Result.success()",
     "val unread = (stats.inbox?.total ?? 0)"),
    # announcing a first read as arrivals: the first-read branch falls through
    ("android/app/src/main/java/com/grapsee/grapmail/logic/MailPollWorker.kt",
     '        if (!hadPrevious) {\n            report("silent first-read unread=$unread")\n            return Result.success()\n        }\n',
     '        if (!hadPrevious) {\n            report("silent first-read unread=$unread")\n        }\n'),
    # claiming delivery in the notification line
    ("android/app/src/main/java/com/grapsee/grapmail/logic/Notifications.kt",
     '.setContentText("$total unread in the inbox")',
     '.setContentText("$total messages delivered")'),
    # posting without checking the grant
    ("android/app/src/main/java/com/grapsee/grapmail/logic/Notifications.kt",
     "        if (!allowed(context)) return false",
     "        // post regardless; the system will drop it if it disagrees"),
    # the offline banner claiming before the device answers
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MailboxScreen.kt",
     "if (state.online == false) {",
     "if (state.online != true) {"),
    # the connectivity callback left registered after the model dies
    ("android/app/src/main/java/com/grapsee/grapmail/logic/MailboxViewModel.kt",
     "        (networkCallback?.let { cb -> manager?.runCatching { unregisterNetworkCallback(cb) } })",
     "        // the system drops it when the process goes"),
    # the shortcut declared but never routed
    ("ios/GrapMail/RootView.swift",
     "                shortcuts.consume()",
     "                // left pending so the next launch can act on it too"),
    # a signed-out shortcut landing on a composer it cannot send from
    ("android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt",
     "            nav.navigate(Routes.SIGN_IN) { popUpTo(Routes.WELCOME) }",
     "            nav.navigate(Routes.mailCompose(\"\", \"reply\"))"),
    # predictive back switched off again
    ("android/app/src/main/AndroidManifest.xml",
     'android:enableOnBackInvokedCallback="true"',
     'android:enableOnBackInvokedCallback="false"'),
    # a half-verified App Link, shipped because the file "should" appear
    ("android/app/src/main/AndroidManifest.xml",
     "            <meta-data\n                android:name=\"android.app.shortcuts\"",
     "            <intent-filter android:autoVerify=\"true\">\n"
     "                <action android:name=\"android.intent.action.VIEW\" />\n"
     "            </intent-filter>\n"
     "            <meta-data\n                android:name=\"android.app.shortcuts\""),
    # push claimed where there is no push service
    ("ios/project.yml",
     "        UIBackgroundModes:\n          - fetch\n",
     "        UIBackgroundModes:\n          - fetch\n          - remote-notification\n"),
]


def run_gate() -> tuple[int, str]:
    proc = subprocess.run([sys.executable, str(ROOT / "scripts/mail-honesty.py"), "--quiet"],
                          capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


def main() -> int:
    undetected = []
    for rel, old, new in MUTATIONS:
        path = ROOT / rel
        original = path.read_text(encoding="utf-8")
        if old not in original:
            print(f"SKIP (anchor missing): {rel}: {old[:48]!r}")
            undetected.append((rel, "anchor not found"))
            continue
        path.write_text(original.replace(old, new, 1), encoding="utf-8")
        try:
            code, out = run_gate()
        finally:
            path.write_text(original, encoding="utf-8")
        failed = [ln.strip()[5:] for ln in out.splitlines() if ln.strip().startswith("FAIL")]
        bit = code != 0 and bool(failed)
        print(f"{'BIT  ' if bit else 'MISSED'} {Path(rel).name}: "
              f"{'; '.join(failed)[:110] if bit else 'the gate accepted it'}")
        if not bit:
            undetected.append((rel, f"exit={code}"))
    restored = subprocess.run([sys.executable, str(ROOT / "scripts/mail-honesty.py"), "--quiet"],
                              capture_output=True, text=True)
    tail = (restored.stdout.strip().splitlines() or [""])[-1]
    print(f"\nrestored tree: {tail or restored.stderr.strip()}")
    if undetected:
        print(f"{len(undetected)} mutation(s) not caught")
        for rel, why in undetected:
            print(f"  - {rel} ({why})")
        return 1
    print(f"all {len(MUTATIONS)} seeded regressions refused")
    return 0


sys.exit(main())
