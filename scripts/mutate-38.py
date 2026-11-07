#!/usr/bin/env python3
"""Seed one regression per §38 rule and prove the gate refuses it.

Each mutation is applied to the real source, `mail-honesty.py` is run, and the
file is restored whether the run passed or failed. A mutation that the gate
accepts is a pin that does not hold anything.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MUTATIONS = [
    ("android/app/src/main/java/com/grapsee/grapmail/logic/MailboxViewModel.kt",
     "MailStep(it.threadId ?: it.id, it.threadId != null)",
     "MailStep(it.threadId ?: it.id, true)",
     "the step target decides the route from the row"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/ThreadScreen.kt",
     "if (message.isRead) {",
     "if (true) {",
     "the unread control appears only while the message is read"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/ThreadScreen.kt",
     "vm.markUnread(message.id) { outcome ->",
     "vm.actOn(message.id, MailAction.Unread) { outcome ->",
     "the reader can mark its own message unread"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/ThreadScreen.kt",
     '"Loading them tells the sender this message was opened.",',
     '"Images load from the sender.",',
     "remote images are named and gated"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt",
     'if (scheme != "http" && scheme != "https") return null',
     'if (scheme.isEmpty()) return null',
     "only an absolute http(s) image address is ever considered"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt",
     ".cookieJar(CookieJar.NO_COOKIES)",
     "// cookies ride along",
     "the image loader carries no session and no cookies"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt",
     'if (!type.startsWith("image/")) {',
     'if (false) {',
     "a response is only drawn when the server says it is an image"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt",
     "manager.print(docName, adapter, PrintAttributes.Builder().build())",
     "runCatching { WebView(context).loadUrl(docName) }; manager.print(docName, adapter, PrintAttributes.Builder().build())",
     "neither print path renders HTML anywhere"),
    ("android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/ThreadScreen.kt",
     "{ onReply(\"all\") }, fill = false)",
     "{ onReply(\"all\") })",
     "the reader's action rows size their buttons to their content"),
    ("ios/GrapMail/MailReaderViews.swift",
     "if message.isRead {",
     "if true {",
     "the unread control appears only while the message is read"),
    ("ios/GrapMail/MailHandoff.swift",
     "config.httpCookieAcceptPolicy = .never",
     "// cookies ride along",
     "the image loader carries no session and no cookies"),
    ("ios/GrapMail/MailHandoff.swift",
     "guard scheme == \"http\" || scheme == \"https\" else { return nil }",
     "guard !scheme.isEmpty else { return nil }",
     "only an absolute http(s) image address is ever considered"),
    ("ios/GrapMail/MailHandoff.swift",
     "controller.printFormatter = UISimpleTextPrintFormatter(",
     "controller.printHTMLURL = URL(string: \"https://example.com\")!; controller.printFormatter = UISimpleTextPrintFormatter(",
     "neither print path renders HTML anywhere"),
    ("ios/GrapMail/MailboxModel.swift",
     "MailStep(id: row.threadId ?? row.id, isThread: row.threadId != nil)",
     "MailStep(id: row.threadId ?? row.id, isThread: true)",
     "the step target decides the route from the row"),
    ("ios/GrapMail/RootView.swift",
     ".mailThread(id: step.id, isThread: step.isThread)",
     ".mailThread(id: step.id, isThread: true)",
     "the step target decides the route from the row"),
    ("ios/GrapMail/MailSnapshot.swift",
     "s.rows = MailSnapshotView.rows",
     "// rows not seeded",
     "the thread frames seed the rows the stepper counts"),
    ("scripts/android-walk.sh",
     "116-mail-thread-images",
     "117-mail-thread-images",
     "116-mail-thread-images is a real frame on both platforms"),
]


def run_gate() -> tuple[int, str]:
    proc = subprocess.run([sys.executable, str(ROOT / "scripts/mail-honesty.py"), "--quiet"],
                          capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


def main() -> int:
    undetected = []
    for rel, old, new, expect in MUTATIONS:
        path = ROOT / rel
        original = path.read_text(encoding="utf-8")
        if old not in original:
            print(f"SKIP (anchor missing): {rel}: {old[:40]}")
            undetected.append((rel, expect, "anchor not found"))
            continue
        path.write_text(original.replace(old, new, 1), encoding="utf-8")
        try:
            code, out = run_gate()
        finally:
            path.write_text(original, encoding="utf-8")
        bit = code != 0 and expect in out
        print(f"{'BIT  ' if bit else 'MISSED'} {rel.split('/')[-1]}: {expect}")
        if not bit:
            undetected.append((rel, expect, f"exit={code}"))
    restored = subprocess.run([sys.executable, str(ROOT / "scripts/mail-honesty.py"), "--quiet"],
                              capture_output=True, text=True)
    print(f"\nrestored tree: {restored.stdout.strip().splitlines()[-1] if restored.stdout.strip() else restored.stderr}")
    if undetected:
        print(f"{len(undetected)} mutation(s) not caught")
        for rel, expect, why in undetected:
            print(f"  - {rel}: {expect} ({why})")
        return 1
    print(f"all {len(MUTATIONS)} seeded regressions refused")
    return 0


sys.exit(main())
