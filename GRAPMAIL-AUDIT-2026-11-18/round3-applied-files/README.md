# Round 3 applied files — verify-before-register (2026-11-18)

Byte-exact post-edit state of the two edited files, applied to the mobile
tree this session and verified against the live mount.

| file | md5 (post-edit) |
|---|---|
| MainActivity.kt | 3c42c510beb29b7daa4e55fdf1c4db8d |
| RootView.swift  | 36a709ef86007441fdae0e1635cda9b8 |

How to verify locally:

    md5sum android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt
    md5sum ios/GrapMail/RootView.swift

Full ladder log: `r3-verify-log.txt` (same folder).
