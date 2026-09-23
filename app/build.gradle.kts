import java.time.Duration

import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

// Git metadata — available in both CI and local builds.
// gitCommitCount is intentionally NOT used as the canonical versionCode because
// Git history can change via rebase/squash/shallow-clone/fork, which would silently
// change the build number of an identical source tree.
val gitCommit = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.get().trim().ifEmpty { "unknown" }

val gitCommitCount = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

// CI monotonically increasing build number, falls back to gitCommitCount for local builds.
// Play Store requires versionCode to never decrease: CI builds start at 10000+ so they
// never collide with any pre-existing published versionCode (< 10000 assumed).
val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val computedVersionCode = if (githubRunNumber > 0) {
    10000 + githubRunNumber
} else {
    // Local build: use gitCommitCount as a rough proxy (non-monotonic, but deterministic per checkout)
    100 + gitCommitCount
}

// ── Stable sideload signing ─────────────────────────────────────────────────────
// Owner pain (2026-09-16): every CI build was signed with a fresh runner-local *debug*
// keystore, so each APK carried a different signature and Android refused to install it
// over the previous one. Updating meant uninstalling first — which wiped the Room DB,
// every recording and raw log, and all granted permissions (Bluetooth, location,
// notifications). One stable key turns an update into an in-place install: data and
// permissions survive, exactly like a store update.
//
// keystore/sideload.p12 is a DEDICATED sideload key, committed on purpose together with
// its public password. This repo distributes its own APKs through GitHub Releases, so an
// update's integrity comes from write access to the repo (plus the SHA-256 published next
// to the APK), not from the secrecy of this key. It is deliberately separate from the Play
// upload key (my-upload-key.jks), which stays out of the repo — publishing to Play later
// is unaffected and this key can be rotated at any time.
//
// Precedence: if SIDELOAD_KEYSTORE_PATH / SIDELOAD_STORE_PASSWORD / SIDELOAD_KEY_PASSWORD
// are set (e.g. decoded from GitHub Actions secrets), they win and the committed key is
// never read — so the repo can move to secret-held signing without any code change.
// Blank counts as absent: GitHub Actions sets an unset secret to the EMPTY STRING rather
// than leaving it undefined, and an empty password would silently break signing instead
// of falling back to the committed key.
fun envOrFallback(key: String, fallback: String): String =
    System.getenv(key)?.takeUnless { it.isBlank() } ?: fallback

val sideloadStoreFile: File =
    System.getenv("SIDELOAD_KEYSTORE_PATH")?.takeUnless { it.isBlank() }?.let { file(it) }
        ?: file("${rootDir}/keystore/sideload.p12")
val sideloadStorePassword: String = envOrFallback("SIDELOAD_STORE_PASSWORD", "kylaq.sideload.public")
val sideloadKeyPassword: String = envOrFallback("SIDELOAD_KEY_PASSWORD", "kylaq.sideload.public")
val sideloadKeyAlias: String = envOrFallback("SIDELOAD_KEY_ALIAS", "sideload")

// Human-readable version: CI builds read "1.0.<run number>", local builds say so outright.
val computedVersionName = if (githubRunNumber > 0) "1.0.$githubRunNumber" else "1.0.0-local"

// In-app updater feed. The rolling GitHub Release always serves the newest build under
// these two stable names, and the repo is public, so the phone needs no token to read them.
val updateRepoSlug = System.getenv("GITHUB_REPOSITORY") ?: "tritonelabautomation/KylaqOBD2Scan"
val updateFeedUrl = "https://github.com/$updateRepoSlug/releases/latest/download/latest.json"
val updateApkUrl = "https://github.com/$updateRepoSlug/releases/latest/download/KylaqOBD2Scan.apk"
// Crash auto-report token: optional fine-grained PAT with issues:write + contents:write for crash-auto label.
// Stored as secret CRASH_REPORT_TOKEN in GitHub Actions and as .env CRASH_REPORT_TOKEN locally.
// Empty = no GitHub auto-push, crash still saved to crash_logs + Drive backup.
val crashReportToken = System.getenv("CRASH_REPORT_TOKEN") ?: ""

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  id("com.google.firebase.crashlytics") version "3.0.3"
}

// Unit-test hygiene: a hung test must fail the job in minutes, not zombie-run for hours,
// and every test start/result is logged so the CI digest can name the offender.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(12))
    testLogging {
        events("started", "passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Robolectric suites download multi-hundred-MB android-all jars when the tests run and
// initialise full Android sandboxes; on CI that alone exceeded the 12-minute cap twice and
// blocked every feedback loop. They stay part of local/Android-Studio verification, while CI
// runs the pure-JVM suites (trace replay, DTC, telemetry store, drive intelligence, trip
// fuel, decoders, VIN authority) which cover the protocol and analytics core.
if (providers.gradleProperty("ciExcludeRobolectric").isPresent) {
    tasks.withType<Test>().configureEach {
        filter {
            excludeTestsMatching("com.example.BackupAndImportTest")
            excludeTestsMatching("com.example.CatalogSelectionTest")
            excludeTestsMatching("com.example.ExampleRobolectricTest")
            excludeTestsMatching("com.example.ExampleUnitTest")
            excludeTestsMatching("com.example.KylaqMasterRepairTest")
            excludeTestsMatching("com.example.PidDiscoveryServiceTest")
        }
    }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.obdlogger.kxmpzq"
    minSdk = 24
    targetSdk = 36
    versionCode = computedVersionCode
    versionName = computedVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "GIT_COMMIT", "\"${gitCommit}\"")
    buildConfigField("String", "GIT_COMMIT_COUNT", "\"${gitCommitCount}\"")
    buildConfigField("String", "GITHUB_RUN_NUMBER", "\"${githubRunNumber}\"")
    // Where the in-app updater looks for the newest build (see update/AppUpdateFeed.kt).
    buildConfigField("String", "UPDATE_FEED_URL", "\"${updateFeedUrl}\"")
    buildConfigField("String", "UPDATE_APK_URL", "\"${updateApkUrl}\"")
    // Auto crash → GitHub Issues (optional, empty = disabled, still local + Drive)
    buildConfigField("String", "CRASH_REPORT_TOKEN", "\"${crashReportToken}\"")
    buildConfigField("String", "CRASH_REPORT_REPO", "\"${updateRepoSlug}\"")
  }

  signingConfigs {
    // One stable key for every sideloaded build (see the note at the top of this file).
    create("sideload") {
      storeFile = sideloadStoreFile
      storePassword = sideloadStorePassword
      keyAlias = sideloadKeyAlias
      keyPassword = sideloadKeyPassword
    }
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Stable signature: each new CI build installs in-place over the previous one, so
      // updating never uninstalls the app and never costs the owner their data or
      // permissions. Guarded so a checkout without the key material still builds, falling
      // back to Gradle's per-machine debug keystore (updates then need a re-install).
      if (sideloadStoreFile.exists()) {
        signingConfig = signingConfigs.getByName("sideload")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.auth)
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.app.projected)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation("com.google.firebase:firebase-crashlytics:19.4.4")
  implementation("com.google.firebase:firebase-analytics:22.4.0")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  // Real org.json for JVM tests: the android.jar copy is a throwing stub, which made
  // GeminiTextClient.parseReceiptJson swallow "Stub!" and return null in unit tests.
  testImplementation("org.json:json:20240303")
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
