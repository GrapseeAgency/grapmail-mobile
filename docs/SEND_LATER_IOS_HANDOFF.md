# Send later — iOS hand-off spec (build next round)

Status: **Android built (2026-11-24, v0.0.1)**. iOS: spec only. No iOS file was edited this round.
Android reference: `android/.../ui/screens/mail/SendLaterSheet.kt`, `SendLaterSnackbar.kt`,
`ComposeScreen.kt` (search "send later"), `logic/MailboxViewModel.kt` (`scheduleSend`, `undoScheduledSend`),
`data/MailDto.kt` (`MailSchedule`), `data/Api.kt` (`MailSendRequest.sendAt`).

## 1. Backend contract (no new endpoints)

| What | Call | Notes |
|---|---|---|
| Schedule | `POST /grapsee/grapmail/send` with the normal send body **plus `sendAt`** | `services/shared/src/routes/grapmail.ts` → `parseScheduledFor(body.sendAt)` |
| Response | `{ success, id, threadId, status: "scheduled", scheduledFor: "<ISO>", deliveryError }` | `scheduledFor` is the server's own instant: show **that**, not the one you sent |
| List | `GET /grapsee/grapmail/scheduled` | already used by `ScheduledView.swift` (`MailAPI.mailScheduledList`) |
| Undo / cancel | `DELETE /grapsee/grapmail/scheduled/:id` | already in `MailAPI.mailScheduledCancel`. The row goes **back to Drafts** (body and attachments kept), so it is not deleted |

`sendAt` rules (`services/shared/src/lib/mail-schedule.ts`). Check them on the client too:
- ISO 8601 **with a zone** (`Z` or `±hh:mm`). Send UTC, whole seconds: `2026-11-25T02:00:00Z`
  (`ISO8601DateFormatter` with `.withInternetDateTime`, timeZone UTC).
- At least **60 s** ahead and at most **30 days** (30 × 86 400 000 ms) ahead of server time. Otherwise the
  server returns 400 and **never** sends it immediately.
- Omit the key (or send `null`) for a normal send. Use `encodeIfPresent` so existing sends and Outbox
  bodies stay byte-identical, which keeps their idempotency keys the same.

## 2. Model changes (`MailAPI.swift`, `MailboxModel.swift`)

1. `MailSendRequest`: add `var sendAt: String? = nil` (encode-if-present).
2. `ComposeState`: add `scheduledId: String?` and `scheduledFor: String?`. Add `"scheduled"` to `accepted`
   (`MailboxModel.swift:187`) so the composer cannot double-send.
3. `func scheduleSend(at: Date, e2eEnvelope: String? = nil, attachmentIds: [String] = [])`:
   - The same refusals as `send()`: empty To gives "Add at least one recipient in To.", and the window check
     (too soon / too far) gives the composer error plus a failure haptic.
   - **No 5-second undo hold.** Nothing leaves before the chosen time, and the receipt's Undo is the take-back.
   - Let an in-flight autosave settle, read the live `draftId`, then run the normal send path with `sendAt`.
   - Treat `status == "scheduled"` as accepted: delete the old draft row (as for a sent message), invalidate
     Drafts/Sent, play `GMHaptics.success`, and set `resultStatus`, `scheduledId` and `scheduledFor` in **one**
     state write. Insert the row into the scheduled list state at once, sorted by time.
   - **Offline:** do **not** enqueue in `Outbox.shared`. Android found that its Outbox worker counts only
     sent/queued/delivered as accepted, so a scheduled answer is reported as a failure and the draft is
     orphaned. A time can also expire while the message waits. Check whether iOS `Outbox.swift` has the
     same `accepted` rule. If it does, show "No connection, so nothing was scheduled. Your message is still
     here." and keep the composer open. If the Outbox is later taught `"scheduled"` and expiry, scheduling
     can join that path.
4. `func undoScheduledSend(id:) async -> String?` calls `mailScheduledCancel`, removes the row from the
   scheduled list, invalidates Drafts, and plays a marking haptic. It returns nil on success or the reason.

## 3. UI (`MailReaderViews.swift` compose view)

**Clock button** next to Send in the compose toolbar. Add one beside the bottom Send button too if the view has one.
- SF Symbol `clock`. `.accessibilityLabel("Schedule send")`, `.accessibilityHint("Choose when to send")`,
  `.accessibilityAddTraits(.isButton)`. Disabled under the same conditions as Send (sending, uploading,
  preparing/sealing, accepted, undo window).
- If To is empty, show the need-recipient notice instead of opening the sheet. Otherwise dismiss the
  keyboard and present the sheet.

**Sheet** (`.sheet` with `.presentationDetents([.medium, .large])`, `.presentationDragIndicator(.visible)`;
the content is a `ScrollView`, so a short landscape window can reach every row):

| Row | Time (device time zone `TimeZone.current`, wall clock) | Shown when |
|---|---|---|
| Later today | now + 3 h, rounded **up** to :00/:30 (14:07→17:30; exact :30 stays) | hour < 20 **and** the result is still today |
| Tomorrow morning | tomorrow 08:00 | always |
| Monday morning | next Monday 08:00 (a week ahead on a Monday) | **not** on Sunday (it would equal Tomorrow) |
| Pick date & time | opens the picker | always, with detail "Up to 30 days ahead" |

- Each row shows a label with the formatted time **below** it (stacked, never truncated, so Dynamic Type up
  to AX5 only makes rows taller). VoiceOver reads it as one element: "Tomorrow morning, Wed 8:00 AM".
- Footer: "Times are in your time zone: <TimeZone.current.localizedName(.generic, locale)>".
- Recompute the options every 30 s while open, so at 19:59→20:00 "Later today" disappears.
- Build dates with `Calendar.current.nextDate(...)` or `date(bySettingHour:...)`. A DST gap moves forward.
- On iPad / regular width, keep the content at most about 560 pt wide.

**Pick date & time:** one `DatePicker` with `[.date, .hourAndMinute]` and `in: Date().addingTimeInterval(60)...Date().addingTimeInterval(30*86400)`.
Use `.graphical` style in regular height and `.compact` in compact height (landscape phone) or at
accessibility Dynamic Type sizes. The 12/24 h clock follows the system automatically. On **Schedule**, run
the window check again: show "Pick a time at least a minute from now." / "Pick a time no more than 30 days
ahead." inline (announced with `UIAccessibility.post(.announcement)`) and keep the picker open.

**Time format** for rows and receipt: `Date.FormatStyle` with weekday(.abbreviated), hour and minute. Add
month and day when the time is more than 6 days away. This gives "Tue 8:00 AM" or "Tue 08:00" depending on
the system 12/24 h setting.

**While scheduling:** the Send button reads "Scheduling…".

**After `scheduled`:** play the success haptic, close the composer, and show a toast/snackbar overlay **on the
screen underneath** with "Scheduled for Tue 8:00 AM" and **Undo** and **View** buttons.
- Undo calls `undoScheduledSend`. The text becomes "Cancelling…", then "Not scheduled any more. The message
  is back in Drafts." or "Could not cancel: <reason>".
- View navigates to `ScheduledView` (the same route as the `grapmail://scheduled` deep link).
- Keep it about 8 s, longer with VoiceOver running. It is a polite announcement. At large Dynamic Type,
  stack the buttons under the text.
- Refresh the Live Activity: call `SendLaterActivityManager` with the new scheduled list so the
  countdown starts at once. Today it only refreshes when ScheduledView loads.

## 4. Strings (English, same text as Android `res/values/strings_send_later.xml`)
Schedule send · Choose when to send · Send later · Later today · Tomorrow morning · Monday morning ·
Pick date & time · Up to 30 days ahead · Times are in your time zone: %@ · Choose a date · Choose a time ·
Next · Schedule · Cancel · Type the time · Use the clock · Pick a time at least a minute from now. ·
Pick a time no more than 30 days ahead. · Scheduling… · No connection, so nothing was scheduled. Your
message is still here. · Scheduled for %@ · Undo · View · Cancelling… · Not scheduled any more. The
message is back in Drafts. · Could not cancel: %@ · Dismiss

## 5. Acceptance checks
1. Schedule "Tomorrow morning". The composer closes, the toast shows the server's time, and Scheduled lists it.
2. Undo moves it to Drafts and removes it from Scheduled.
3. With an empty To, the clock shows the recipient notice and no sheet opens.
4. Scheduling is unavailable while an attachment is uploading.
5. Airplane mode gives the error, the composer stays open, and nothing is in the Outbox.
6. A picked time 30 s ahead shows an inline error and the server is not called.
7. At 20:30, "Later today" is hidden. On Sunday, "Monday morning" is hidden.
8. iPhone landscape, iPad, and AX5 Dynamic Type: every row is reachable and nothing is truncated.
   VoiceOver reads the clock as "Schedule send, button".
9. With HTML compatibility findings, "Send anyway" schedules (it does not send now). "Keep editing" followed
   by a normal Send sends now.
