import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val voiceVersion = "0.6.22"
val legacyDevBridge = providers.gradleProperty("legacyStandaloneDevBridge").orNull == "true"

android {
    namespace = "se.takcampus.voice.watch"
    compileSdk = 34
    defaultConfig {
        applicationId = "se.takcampus.voice.standalone"
        minSdk = 30
        targetSdk = 34
        manifestPlaceholders["voiceBindPermission"] = "se.takcampus.voice.watch.permission.BIND_PLUGIN"
        versionCode = 82
        versionName = voiceVersion
    }
    flavorDimensions += "integration"
    productFlavors {
        create("standalone") {
            dimension = "integration"
            resValue("string", "voice_app_name", "Voice $voiceVersion")
            resValue("string", "connection_status", "Standalone $voiceVersion\\n%1\$s\\n%2\$s")
        }
        create("plugin") {
            dimension = "integration"
            applicationId = "se.takcampus.voice.watch"
            resValue("string", "voice_app_name", "WearTAK Voice Plugin")
        }
    }
    if (!legacyDevBridge) {
        sourceSets.maybeCreate("standaloneDebug").manifest.srcFile("src/standaloneWifiDebug/AndroidManifest.xml")
    }
    signingConfigs {
        create("voiceRelease") {
            System.getenv("VOICE_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("VOICE_KEYSTORE_PASSWORD")
            keyAlias = "weartak-voice"
            keyPassword = System.getenv("VOICE_KEYSTORE_PASSWORD")
        }
    }
    buildTypes { getByName("debug") { applicationIdSuffix = ".dev"; manifestPlaceholders["voiceBindPermission"] = "se.takcampus.voice.permission.BIND_PLUGIN_DEV" }; getByName("release") { isMinifyEnabled = false; if (System.getenv("VOICE_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("voiceRelease") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug").withFlavor("integration" to "standalone")) { variant ->
    if (!legacyDevBridge) variant.applicationId.set("se.takcampus.voice.standalone.wifi.dev")
    variant.outputs.forEach { it.versionName.set("$voiceVersion-dev") }
}

val pluginSdkAar = providers.gradleProperty("weartakSdkAar").orElse(
    rootProject.file(".gradle/sdk/86bdc8b04797-a818af15b332/build/outputs/aar/weartak-plugin-sdk-release.aar").absolutePath)
val verifyPluginSdk by tasks.registering {
    inputs.file(pluginSdkAar)
    doLast {
        val artifact = file(pluginSdkAar.get())
        require(artifact.isFile) { "Build the pinned SDK with python ci/build_plugin_sdk.py first" }
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(digest in setOf("6ec84b18e1c8ac6b4be727083c4ea7a572150b521384270212434c239e5a1e0b", "8f911b81ac85e691ad182097fe0ef0f00ffa0d2da3442d3d2f03c5ec2529184f")) { "Unexpected combined plugin SDK artifact" }
    }
}
tasks.matching { it.name == "prePluginDebugBuild" || it.name == "prePluginReleaseBuild" }.configureEach { dependsOn(verifyPluginSdk) }

dependencies {
    implementation(project(":core"))
    implementation(project(":voice-package-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    "pluginImplementation"(files(pluginSdkAar))
}
