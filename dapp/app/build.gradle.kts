plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.linmjt.whisperofsol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.linmjt.whisperofsol"
        minSdk = 24
        targetSdk = 37
        versionCode = 105
        // v1.9.0: Clean devnet reset, full SOP validation.
        //   old: Dv1UzYXq... (v1.8.33, closed 2026-10-08 via SOP Step 1)
        //   old treasury: DAmnstxv... (was)
        //   old jackpot:  D5sFx5G...  (was)
        //   new: CCTfaFAHLPHQJrEcfKvvbeCKoEL2S5uFogev2DUo1kKw
        //   new treasury: 4k4Uu9dAXczWGexPwyHFh7m62PHHGBXJ58ej34JKajHc
        //   new jackpot:  HZ6Wixru6GjySL9E1t9arsSrf445kxoNuS5n9vcQVVxE
        // v2.0.1 (2026-10-09): Lottery Rules — removed Program ID card.
        // v2.0.2 (2026-10-09): Login button — removed "(no GAS)" suffix.
        // v3.0.0 (2026-10-09): In-app language switcher (Settings).
        // v3.0.1 (2026-10-09): Fix language switcher not applying locale.
        //   Made MainActivity extend AppCompatActivity.
        // v3.0.2 (2026-10-09): Fix v3.0.1 launch crash.
        //   Theme parent → Theme.AppCompat.DayNight.NoActionBar.
        // v3.0.3 (2026-10-09): Removed manual recreate() to fix double-recreate
        //   race condition with AppCompatActivity's auto-recreate.
        //   Result: still crashed on language switch. AppCompatDelegate
        //   setApplicationLocales() itself is incompatible with this activity
        //   setup for unknown reasons.
        // v3.0.4 (2026-10-09): REWRITE — drop AppCompatDelegate entirely.
        //   Switched to manual locale management via SharedPreferences.
        // v3.0.5 (2026-10-09): Drop activity.recreate() — use
        //   CompositionLocalProvider. v3.0.5 BROKE: the wrapped Context
        //   broke Compose internals, app couldn't even open.
        //   User feedback (Telegram 23:18 GMT+8): "完全無法開啟"
        // v3.0.6 (2026-10-09): Use Resources.updateConfiguration directly
        //   on the activity's resources. Deprecated in API 25+ but still
        //   functional. Triggers a Compose recomposition via LanguageState
        //   so the next stringResource() call returns the new locale.
        //   No recreate, no createConfigurationContext, no
        //   CompositionLocalProvider — simplest possible in-place locale
        //   switch that doesn't break Compose.
        versionName = "3.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // DEBUG build: full file log (DEBUG + INFO + ERROR)
            buildConfigField("boolean", "FILE_LOG_FULL", "true")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // RELEASE build: only error log to file (saves space)
            // Can be overridden at runtime via: adb shell setprop debug.app.verbose true
            buildConfigField("boolean", "FILE_LOG_FULL", "false")
            // ⚠️ hackathon demo 用: release 也用 debug key 簽章
            // 正式上 Solana dApp Store 需要換成正式 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9.x: Kotlin jvmTarget 自動跟著 compileOptions (不再用 kotlinOptions)

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // v3.0.0 (2026-10-09): In-app language switcher (Settings → Language)
    // AppCompatDelegate.setApplicationLocales() handles per-app locale
    // selection across Android 13+ (auto via manifest metadata) and
    // Android 6-12 (we manually call the API and recreate the activity).
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ─── Solana Mobile Stack ─────────────────────────────────────
    // MWA 2.2.0 — connect to Seed Vault wallet (Saga/Seeker)
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.2.0")

    // Solana RPC + transaction building (官方 solana-kotlin-sdk 系列)
    // 0.3.2-beta6 有 setFeePayer/addFeePayer API,修復 fee payer bug
    implementation("com.solanamobile:web3-solana-jvm:0.3.2-beta6")

    // ─── HTTP (Solana RPC) ────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ─── Crypto (X25519 + AES-GCM) ────────────────────────────
    // 使用 Tink 提供的 X25519 (com.google.crypto.tink.subtle.X25519)
    // + Java standard library AES/GCM
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    // ─── AndroidX Security Crypto ──────────────────────────────
    // EncryptedSharedPreferences + MasterKey (Android Keystore-backed AES-GCM)
    // Used by WhisperKeyStore to cache the signature-derived X25519 secret
    // across sessions without exposing it on disk in plaintext.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ─── BouncyCastle (RFC 7748 Ed25519 → X25519 conversion) ───────
    // 提供 Ed25519 → Montgomery curve 標準推導 (與 libsodium / NaCl 相容)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ─── CameraX (QR scanner preview, Bug 2026-10-02 10:55 fix) ───────
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ─── ML Kit Barcode Scanning (offline, on-device) ───────────────
    // User rejected the 'disabled QR' approach — need real QR scanning.
    // ML Kit scans barcodes locally without Google Play Services deps.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ─── ZXing core (QR code generation, v1.9.7) ─────────────────
    // Used to render the user's wallet address as a QR code in the
    // integrated YOUR WALLET card (tap wallet address → QR dialog).
    // Pure Java, no Android dependencies, ~700KB.
    // ML Kit only does scanning, not generation.
    implementation("com.google.zxing:core:3.5.3")

    // ─── JSON via Android built-in org.json ───────────────────────
    // 不需 kotlinx-serialization

    // Debug / tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ─── Paparazzi (screenshot testing) ─────────────────────────────
    testImplementation("app.cash.paparazzi:paparazzi:1.3.5")
    testImplementation("junit:junit:4.13.2")
}
