import ActivityKit
import Foundation

/// The app half of the send-later Live Activity: started from the Scheduled
/// screen's own read — and from the composer's, right after a send is
/// scheduled or its Undo lands (`MailboxModel.refreshScheduledActivity`) —
/// ended when that read no longer shows a send to count down to. Nothing here decides *when* mail is sent — `scheduled_at` on the
/// server does, exactly as the Scheduled screen says — this only mirrors the
/// schedule onto the Lock Screen.
///
/// ── WHY "SOONEST WITHIN 24H", ONE ACTIVITY ─────────────────────────────────
/// The Lock Screen is for what happens next; a send three days out is not
/// next. The manager keeps exactly the soonest send inside 24 hours alive and
/// ends every other activity, so a cancelled send disappears the moment the
/// list it lived in no longer has it, and the next soonest send — if any —
/// takes over in the same beat.
@available(iOS 16.2, *)
enum SendLaterActivityManager {
    /// Mirrors `messages` (the server's list, soonest first) onto the Lock
    /// Screen. Idempotent: safe to call after every load and every cancel.
    static func sync(messages: [MailScheduledMessage]) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let soonest = nextSend(in: messages)
        Task {
            // End everything that is not the one send this manager is for.
            for activity in Activity<SendLaterAttributes>.activities
            where activity.attributes.scheduledId != soonest?.id {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            guard let soonest, let scheduledFor = parseDate(soonest.scheduledFor),
                  scheduledFor > Date() else { return }
            let state = SendLaterAttributes.ContentState(
                subject: soonest.subject?.nilIfBlank(),
                to: (soonest.to ?? []).joined(separator: ", "),
                scheduledFor: scheduledFor)
            if let existing = Activity<SendLaterAttributes>.activities
                .first(where: { $0.attributes.scheduledId == soonest.id }) {
                await existing.update(ActivityContent(state: state, staleDate: scheduledFor))
            } else {
                _ = try? Activity.request(
                    attributes: SendLaterAttributes(scheduledId: soonest.id),
                    content: ActivityContent(state: state, staleDate: scheduledFor),
                    pushType: nil)
            }
        }
    }

    /// Sign-out: the next person to pick up the phone must not read the last
    /// one's pending send off the Lock Screen.
    static func endAll() {
        Task {
            for activity in Activity<SendLaterAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    /// The soonest future send within the next 24 hours, or nil.
    private static func nextSend(in messages: [MailScheduledMessage]) -> MailScheduledMessage? {
        let horizon = Date().addingTimeInterval(24 * 60 * 60)
        return messages
            .compactMap { message -> (MailScheduledMessage, Date)? in
                guard let date = parseDate(message.scheduledFor),
                      date > Date(), date <= horizon else { return nil }
                return (message, date)
            }
            .min { $0.1 < $1.1 }?.0
    }

    /// The server writes these with milliseconds (`Date.toISOString()`), which
    /// a default `ISO8601DateFormatter` refuses — so every real send parsed to
    /// nil here and no countdown ever started. SendLaterSchedule reads both.
    private static func parseDate(_ iso: String?) -> Date? {
        SendLaterSchedule.parse(iso)
    }
}
