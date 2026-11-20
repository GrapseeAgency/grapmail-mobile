# Round 6 - applied files (GrapMail, 2026-11-18)

Compose hygiene + receipt truth + version gate. Each file below is the post-edit content as verified applied to the working tree by the write-first pipeline (exact sizes + marker sets; project.yml additionally by markers + git diff).

| file | bytes | md5 | role |
| --- | --- | --- | --- |
| ComposeScreen.kt | 35306 | `a69454df70a1c809e67af35dc33f5832` | silent seed path (composeSeed); close predicate gated on `userEdited` |
| MailboxViewModel.kt | 161679 | `a018c24e8b34c4374f59195aca393a97` | `userEdited` flag + autosave guard; `composeSeed`; receipt merged into one server-framed update |
| MailReaderViews.swift | 73245 | `c272a66e1f1c21cf41a39348fce45471` | silent seed path (composeSeed); X/close predicate gated on `userEdited` |
| MailboxModel.swift | 131098 | `bcdbdc5bb25da7bb375ce3ed0b9ee289` | `userEdited` flag + autosave guard; `composeSeed`; receipt now shows server `message`/`deliveryError` verbatim |
| mail-honesty.py | 208800 | `d9c0165419bfc8be16e8d31e71b87889` | +9 can-fail pins closing the seed-autosave blind spot |
| project.yml | 5198 | `538be063907677b245da2c2d3bb1568b` | `MARKETING_VERSION` 0.6.3 to 0.6.4; `CURRENT_PROJECT_VERSION` 12 to 13 (answer Android) |

Read the round note one level up for the full story and verification ladder:
`ROUND6-COMPOSE-HYGIENE-RECEIPT-TRUTH-AND-VERSION-GATE-2026-11-18.md`.
