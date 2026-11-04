plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.grapsee.grapmail"
    compileSdk = 34

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
        targetSdk = 34
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
        versionCode = 6
        versionName = "0.3.2"
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

    debugImplementation("androidx.compose.ui:ui-tooling")
}
