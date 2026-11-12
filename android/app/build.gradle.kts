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
// `google-services.json` HERE — this file only then applies the plugin, and the
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
        // Bumped for the mailbox sector. `versionName` is what the release page
        // and `aapt dump badging` are checked against, so the tag and the APK
        // cannot quietly disagree about which build this is. 0.2.1 is the first
        // tag whose release job actually signed: v0.2.0's run emitted
        // `app-release-unsigned.apk` because the keystore variables were handed to
        // `$GITHUB_ENV` in the same step that built, and the gate refused to
        // publish it — that failed tag stays in history as the attempt.
        // 0.3.0 carries the account-record band, the mailbox write sector, the
        // E2E encryption sector, subscriptions and the store-compliance work.
        // v0.3.0's first release-job run FAILED HERE ON PURPOSE: the tag said
        // 0.3.0, this file still said 0.2.1, and the badging check refused to
        // publish a release page describing a build it could not name. The
        // disagreement was real; the tag was moved to the commit that fixes it.
        // 0.3.2 is the design-and-behavior release: the frame-audit fixes (empty
        // states, centering, destructive colours, toolbar pair), the compose
        // parity, and the non-filling button inset that un-clipped "Check".
        // 0.4.0 is the advanced-completion release, and it is the one the audit
        // asked for by name: artwork drawn in code instead of cropped from the
        // reference, drafts that open in the composer, attachments that actually
        // open, a reader that steps / marks unread / asks before loading a
        // sender's images / hands mail to share and print, and the platform
        // rules that were missing entirely — an iOS icon that had no file, no
        // splash on either platform, haptics tied to the write rather than the
        // tap, a background new-mail read that says what it is evidence of, an
        // offline fact read from the device, and a home-screen Compose shortcut.
        // 0.5.0 is the gateway-auth release: sign-in, sign-up and every account
        // call moved to the service that owns the mailbox, so the signed-in home
        // reads real mail instead of "Couldn't load Inbox" (the seam and its
        // proof are in FINDINGS.md). It also carries the §39 runtime leg with
        // its walk-side fixes, and two iOS layout defects the frame audit found
        // (a 520pt sign-in form on a 402pt screen; a stepper wrapping mid-word).
        // v0.6.0 / versionCode 9: the first release that can be signed with the
        // founder's distribution key (android.yml resolves CI_KEYSTORE_* secrets
        // and refuses to label a test-signed file as founder-signed).
        versionCode = 10
        versionName = "0.6.1"
        // Real account API — same contract the website uses.
        buildConfigField("String", "API_BASE", "\"https://grapsee.com/api\"")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // The system splash screen. Without it the window the person sees between
    // tapping the icon and the first composed frame is the system's blank
    // default — the smallest form of "this is not a native app".
    implementation("androidx.core:core-splashscreen:1.0.1")

    // The new-mail poll. WorkManager is Android's answer to "do this again
    // later even if the app is closed", it enforces a real minimum interval,
    // and it survives a reboot — which is what makes the notification a report
    // of a read the device actually made rather than a timer that dies with a
    // process.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
