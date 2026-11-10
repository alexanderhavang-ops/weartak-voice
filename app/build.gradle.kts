plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "se.takcampus.voice.watch"
    compileSdk = 34
    defaultConfig {
        applicationId = "se.takcampus.voice.standalone"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-audio-onboarding-alpha"
    }
    signingConfigs {
        create("voiceRelease") {
            System.getenv("VOICE_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("VOICE_KEYSTORE_PASSWORD")
            keyAlias = "weartak-voice"
            keyPassword = System.getenv("VOICE_KEYSTORE_PASSWORD")
        }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("voiceRelease") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":core")) }
