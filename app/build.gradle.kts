import java.io.FileInputStream
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven by keystore.properties (git-ignored).
// Without it the release build produces an unsigned APK, which will not sideload.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()

// The dashboard address is build-time configuration, never a credential.
// Override with -PserverUrl=http://host:9119/desktop/ or a `hermes.serverUrl`
// entry in the git-ignored local.properties; the committed fallback is a
// placeholder so the public repository carries no real host.
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) FileInputStream(localPropertiesFile).use { load(it) }
}
val serverUrl = (project.findProperty("serverUrl") as String?)
    ?: localProperties.getProperty("hermes.serverUrl")
    ?: "http://192.168.1.100:9119/desktop/"
val serverUri = URI(serverUrl)
val dashboardHost = serverUri.host ?: "192.168.1.100"
val dashboardPort = (if (serverUri.port > 0) serverUri.port else 9119).toString()

android {
    namespace = "com.nousresearch.hermesdesktop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nousresearch.hermesdesktop"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", '"' + serverUrl + '"')
        manifestPlaceholders["dashboardHost"] = dashboardHost
        manifestPlaceholders["dashboardPort"] = dashboardPort
    }

    buildFeatures {
        buildConfig = true
    }

    if (hasKeystore) {
        val keystoreProperties = Properties().apply {
            FileInputStream(keystorePropertiesFile).use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        getByName("release") {
            // No code shrinking: the app is a thin WebView shell with zero
            // third-party deps, so R8 would only add build time and risk.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // This shell intentionally uses framework widgets and a deprecated
        // back-button override; do not let lint Vital checks fail the build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Intentionally empty: framework-only (android.app + android.webkit),
    // so the APK stays small and the build never touches the network.
}
