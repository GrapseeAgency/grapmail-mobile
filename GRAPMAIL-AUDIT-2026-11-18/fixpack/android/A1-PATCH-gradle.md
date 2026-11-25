# A1 — Add the Coil image-loading dependencies

**File:** `android/app/build.gradle.kts` (app module)
**Where:** inside the `dependencies { … }` block (block starts at line 117 in the audit snapshot), immediately after the coroutines line.

## Anchor (exact, unique)

```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

## Insert after it

```kotlin
    // Attachment thumbnails (audit fixpack A2). Coil 3.0.x is the line built
    // against Kotlin 2.0.x, which this project compiles with (Kotlin 2.0.20).
    // Do not jump to 3.1+ without bumping Kotlin: 3.1.0 needs Kotlin 2.1.10.
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
```

## Notes

- `coil-compose` is the only artifact the thumbnail path needs (it draws from local files). `coil-network-okhttp` is included so the same loader can later fetch `http(s)` images — drop the second line if you want the minimum diff; nothing in this fixpack uses it.
- No manifest or ProGuard changes: `INTERNET` permission already present; Coil ships its own R8 consumer rules.
- Version check (Maven Central, verified 2026-11-18): `3.0.4` (2024-11-25). `3.1.0` (2025-02-04) is built against Kotlin 2.1.10; `3.2+` and `3.6.3` against even newer Kotlin — stay on the 3.0.x line until the project's Kotlin toolchain moves.

## Verify

```bash
cd android
./gradlew :app:assembleDebug          # resolution + compile proof
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i coil
# expect: io.coil-kt.coil3:coil-compose:3.0.4 and coil-network-okhttp:3.0.4 (+ their transitive coils)
```
