import java.time.LocalDate
import java.time.ZoneOffset

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Local builds remain usable without Firebase; the UI reports push unavailable.
// Provision this ignored client configuration separately, never a service-account key.
// The founder's required setup, once the Firebase project exists: create it with the
// package `com.grapsee.grapmail`, add an Android app, and drop the downloaded
// `google-services.json` HERE â€” this file only then applies the plugin, and the
// pushed new-mail path goes live beside the poll.
if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.grapsee.grapmail"
    compileSdk = 36

    // The signing identity is a decision the founder owns, not a build detail:
    // a key that ships once cannot be swapped later without every install
    // reinstalling. So nothing here reads a keystore out of the repo, and nothing
    // here invents one either. CI generates an EPHEMERAL keystore on the runner,
    // passes its location and password as environment variables for that one
    // job, and the APK it signs is labelled as test-signed on the release page.
    // When the real distribution key exists, it goes into the same three
    // variables as repository secrets and this block does not change.
    val ciKeystore = System.getenv("CI_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEYSTORE_ALIAS")
                keyPassword = System.getenv("CI_KEYSTORE_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.grapsee.grapmail"
        minSdk = 26
        targetSdk = 36
        // First public production release. Earlier 0.2.x–0.7.x identifiers were
        // internal/sideload test lineage, not store releases. Testers with a higher
        // sideloaded versionCode must uninstall it before installing 0.0.1; the
        // production line intentionally starts at versionCode 1.
        versionCode = 1
        versionName = "0.0.1"
        // Real account API â€” same contract the website uses.
        buildConfigField("String", "API_BASE", "\"https://grapsee.com/api\"")
        // Play Integrity's cloud project number, provisioned outside the repo
        // the way the Firebase client config is: a blank value is an
        // UNCONFIGURED deployment, and the integrity client treats it as a
        // quiet no-op (the feature is absent, not failing) â€” the same
        // discipline as push. Set it with -PplayIntegrityCloudProject=â€¦ or the
        // PLAY_INTEGRITY_CLOUD_PROJECT environment variable once the project
        // exists on the founder's Play console.
        val integrityProject = System.getenv("PLAY_INTEGRITY_CLOUD_PROJECT")?.takeIf { it.isNotBlank() }
            ?: project.findProperty("playIntegrityCloudProject")?.toString()?.takeIf { it.isNotBlank() }
            ?: ""
        buildConfigField("String", "PLAY_INTEGRITY_CLOUD_PROJECT", "\"$integrityProject\"")
        // Certificate pins for the API hosts (data/Api.kt apiPinner). The source of
        // truth is security/api-pins.json at the repo root: iOS compiles the same
        // file (scripts/generate-pins.py -> ApiPins.swift), and
        // scripts/check-api-pins.py checks it weekly against the live servers and
        // its expiry (.github/workflows/pin-expiry.yml). Its pins are compiled in
        // only while it says "enforce": true. The Gradle property grapmailApiPins
        // or the environment variable GRAPMAIL_API_PINS (comma-separated sha256/...
        // values from scripts/print-api-pins.sh) still override the JSON's pins,
        // and grapmailApiPinsExpires / GRAPMAIL_API_PINS_EXPIRES its expiry. A blank
        // override counts as unset; -PgrapmailApiPins=none switches pinning off.
        // Empty or a single pin means no pinning, and so does a date on or after
        // the expiry: the app then falls back to normal certificate checks.
        val pinSetFile = project.rootProject.file("../security/api-pins.json")
        @Suppress("UNCHECKED_CAST")
        val pinSet: Map<String, Any?> = if (pinSetFile.isFile) {
            groovy.json.JsonSlurper().parse(pinSetFile) as Map<String, Any?>
        } else {
            emptyMap()
        }
        @Suppress("UNCHECKED_CAST")
        val jsonPins = if (pinSet["enforce"] == true) {
            (pinSet["pins"] as? List<Map<String, Any?>>).orEmpty()
                .filter { it["placeholder"] != true }
                .mapNotNull { (it["sha256"] as? String)?.trim()?.takeIf { pin -> pin.isNotEmpty() } }
                .joinToString(",") { "sha256/$it" }
        } else {
            ""
        }
        val apiPins = ((project.findProperty("grapmailApiPins") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("GRAPMAIL_API_PINS")?.takeIf { it.isNotBlank() } ?: jsonPins).replace("\"", "").trim()
        val apiPinHosts = (listOf(pinSet["host"]) + (pinSet["additionalHosts"] as? List<*>).orEmpty())
            .mapNotNull { (it as? String)?.trim()?.lowercase()?.takeIf { host -> Regex("^[a-z0-9.-]+$").matches(host) } }
            .distinct()
            .joinToString(",")
        val apiPinsExpires = ((project.findProperty("grapmailApiPinsExpires") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("GRAPMAIL_API_PINS_EXPIRES")?.takeIf { it.isNotBlank() }
            ?: (pinSet["expires"] as? String) ?: "").trim()
        if (apiPinsExpires.isNotEmpty()) {
            val until = runCatching { LocalDate.parse(apiPinsExpires) }.getOrNull()
                ?: throw GradleException("Certificate pin expiry must be YYYY-MM-DD, got '$apiPinsExpires' (security/api-pins.json)")
            if (apiPins.isNotEmpty() && !LocalDate.now(ZoneOffset.UTC).plusDays(30).isBefore(until)) {
                project.logger.warn("w: certificate pin set expires $apiPinsExpires; the app stops pinning on that date. Refresh security/api-pins.json.")
            }
        }
        buildConfigField("String", "API_PINS", "\"$apiPins\"")
        buildConfigField("String", "API_PIN_HOSTS", "\"$apiPinHosts\"")
        buildConfigField("String", "API_PINS_EXPIRES", "\"$apiPinsExpires\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Explicit, because "release" is a claim about the artifact and this
            // file is the only place that can make it stick: a debuggable release
            // build is a debug build with a misleading name, and the CI gate that
            // publishes to the Releases page now reads the manifest to prove it
            // is not one.
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // JVM unit tests (src/test, see TESTING.md) run against AGP's stub
    // android.jar. ApiException and the transport log through android.util.Log;
    // with this, those calls return defaults instead of throwing "not mocked",
    // so the pure logic can be tested without Robolectric or an emulator.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    // Delivers the baseline profile (`src/main/baseline-prof.txt`) to ART at
    // install time, so the mailbox's hot paths â€” row render, scroll, thread
    // open â€” are precompiled AOT instead of JIT-compiled mid-scroll on the
    // first runs. This is the difference between emulator-smooth and
    // real-device smooth, and it only costs the app its install size.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // The Play review + in-app update flows: the rating ask and the
    // "a new version is available" overlay, both driven by the store itself.
    implementation("com.google.android.play:review:2.0.2")
    // GrapMail plans. Purchases are verified by the server (routes/grapmail-billing.ts),
    // never trusted on the device.
    //
    // Play Billing Library 8, not 7: Google Play refuses new apps and updates
    // built with Billing 7 after 31 Aug 2026 (extension window closed 1 Nov
    // 2026), so a 7.x upload is rejected at the Play Console. 8.0.0 is the
    // newest line whose Kotlin metadata this project's Kotlin 2.0.20 can read
    // (8.1+ moved to Kotlin 2.2). The plain Java artifact is used on purpose:
    // data/PlayBilling.kt wraps the two async calls it needs itself, so no
    // -ktx extension signature (which changed in 8.0) is part of the build.
    implementation("com.android.billingclient:billing:8.0.0")
    // Brand logos (BIMI) are SVG; drawn to bitmaps for the sender avatar.
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.google.android.play:review-ktx:2.0.2")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    // Passkeys: androidx.credentials drives the WebAuthn sheet, its
    // play-services backend talks to the password manager, and the integrity
    // library is the device's verdict request after enrollment. The biometric
    // dependency itself is already declared below (the app lock brought it).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.play:integrity:1.4.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Attachment thumbnails (fixpack A2). Coil 3.0.x is the line built
    // against Kotlin 2.0.x, which this project compiles with (Kotlin 2.0.20).
    // Do not jump to 3.1+ without bumping Kotlin: 3.1.0 needs Kotlin 2.1.10.
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // The system splash screen. Without it the window the person sees between
    // tapping the icon and the first composed frame is the system's blank
    // default â€” the smallest form of "this is not a native app".
    implementation("androidx.core:core-splashscreen:1.0.1")

    // The new-mail poll. WorkManager is Android's answer to "do this again
    // later even if the app is closed", it enforces a real minimum interval,
    // and it survives a reboot â€” which is what makes the notification a report
    // of a read the device actually made rather than a timer that dies with a
    // process.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // The app lock's prompt: androidx.biometric is the platform's own face /
    // fingerprint / credential sheet, and fragment-ktx brings the
    // FragmentActivity that prompt needs.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // The home-screen unread widget: Glance draws it from the same Compose the
    // app is written in; its receiver + provider XML live beside the app's
    // other entry points (AndroidManifest.xml, res/xml).
    implementation("androidx.glance:glance-appwidget:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
