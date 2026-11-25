GrapMail audit 2026-11-19 - fix files (iOS Scheduled wiring, 6 edits)
1) RootView.swift -> ios/GrapMail/RootView.swift (enum mailScheduled + onScheduled closure + dispatch case)
2) MailSecondaryViews.swift -> ios/GrapMail/MailSecondaryViews.swift (onScheduled prop + Scheduled settings row)
3) MailSnapshot.swift -> ios/GrapMail/MailSnapshot.swift (onScheduled seed arg)
Apply: xcodegen (project.yml already auto-includes ScheduledView.swift) -> build -> run ScreenshotWalkUITests.
