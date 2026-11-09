plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "se.takcampus.voice.watch"
    compileSdk = 34
    defaultConfig {
        applicationId = "se.takcampus.voice.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-onboarding-prototype"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":core")); implementation(project(":weartak-sdk")) }
