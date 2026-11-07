# Platform runtime — what the walk observes, and what it only compiles

The walk (`scripts/android-walk.sh`, `ios/GrapMailUITests/ScreenshotWalkUITests.swift`)
photographs screens; screenshots cannot show a vibration, a notification delivered
by the system, or a background read the app did not trigger itself. This file is
what the run summary points at when a platform surface is mentioned.

## The surfaces, and the evidence each one leaves

| Surface | Where the app decides | How CI sees it | What a screenshot cannot say |
|---|---|---|---|
| Background read (WorkManager / BGTaskScheduler) | `MailPollWorker.kt`, `AppIntegration.swift` | Android: `cmd jobscheduler run -f` forces the system job for the package; the worker's own branch goes to logcat tag `GrapMailPoll` and the walk greps it. The walk forces it **twice**: once before the send (stores the baseline the worker compares against), once after (sees the rise the send created) — a single forced run can only report `silent first-read` and would make the notification permanently unobservable. The branch is one of `silent signed-out`, `silent read-refused status=…`, `silent first-read`, `silent no-rise`, `announced +N of M`, `silent post-refused-by-permission` | whether the *schedule* fires at 30 minutes — the forced run proves the worker, not the clock |
| Haptic | `GMHaptics` on both platforms (Android player in `MainActivity`, iOS in `AppIntegration.swift`) | Android: `performHapticFeedback` returns the view's answer, logged as `GrapMailHaptics: played <event> … accepted=<bool>`, and the walk pairs the event with the send receipt it saw. iOS: UIKit's generators return nothing, so the call is emitted and **not observed** — stated as a limit, not claimed | whether a device with a motor physically moved. The emulator has none, so `accepted=false` is the honest reading and is not a failure |
| New-mail notification | `Notifications.kt` / `NotificationCenter` | The forced poll announces a rise; `dumpsys notification --noredact` must hold a record for the package, then the shade is expanded and the posted line is searched for by text | delivery *timing* — a notification the system chose to delay is still a notification the app posted |
| Long-press (shortcut) | `shortcuts.xml` action → intent | The action is read out of `shortcuts.xml`, delivered with `am start -W -a …`; the composer's `mail-to` field must appear | the launcher's own animation — it belongs to the launcher, not the app |
| Notification permission | Settings row → `RequestPermission()` / `requestAuthorization` | The row is tapped; if the system offers a prompt it is answered and the row must re-read `notificationSettings()` and show "On" | nothing further: the grant is the system's state, checked twice |
| Offline claim | `MailboxViewModel` / `MailboxModel` network callback | Airplane mode is toggled by the harness, the banner must appear, the radio is restored, and the banner must leave | whether the radio was genuinely off — the banner is the app's reading of it, and it must disappear when the reading changes |

## What the walk prints

Android writes a `sys_summary` block onto the run page. The lines that matter:

- `* background read: **observed**` — the system ran the job and the worker named a branch.
- `* background read: **NOT observed**` — the job was forced and the worker said nothing. That is the failure the leg exists to surface, and the run page states it rather than counting a silent worker as success.
- `* new-mail notification: **observed**` — the job announced a rise and the shade drew the line.
- `* new-mail notification: **not observed**` — the read was refused (the string carries the server's status). The poll staying silent is the correct branch; it is reported instead of hidden.
- `* new-mail notification: **not due**` — either the job's first run only stored the baseline it compares against, or the unread count did not rise. Both mean no notification *could* be announced, which is different from a notification that failed, and the run page says which.
- `* live mailbox read: **answered / failed**` — whether the signed-in home rendered a mailbox or showed `Couldn't load Inbox`. This is the line that would have named the auth seam.

iOS prints `live-mail: home shows load failure = <bool>` into the test log.

## What remains unproven

1. **The schedule.** A forced job proves the worker's logic; it does not prove
   WorkManager/RBGTaskScheduler honour the interval on a real device under
   battery pressure. That needs a physical phone and time.
2. **A motor.** `accepted=true` is only ever possible on hardware.
3. **The iOS haptic.** `UIImpactFeedbackGenerator` reports nothing back; the
   walk can prove the send receipt and the pairing on Android, and on iOS only
   that the generator exists in the path. That asymmetry is a platform fact.
4. **Push.** There is no push service, so no push is claimed anywhere; §39 pins
   the absence of the manifest entries that would pretend otherwise.
5. **Real IMAP delivery.** The walk's mail comes from the app's own send path;
   an external MTA's timing is outside CI.

## How this stays honest

`scripts/mail-honesty.py` §39 pins the absence of surfaces that would otherwise
be added because the code is nearby (deep-link verification, push background
mode, FCM), and `scripts/mutate-39.py` seeds one regression per pin and requires
the gate to refuse it. A surface that is claimed here without a line in the walk
summary is a claim that does not exist.
