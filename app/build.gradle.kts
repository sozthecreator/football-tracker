import java.util.Properties

plugins {
    id("com.android.application")
}

// API key for football-data.org: put  footballDataKey=XXXX  in local.properties (gitignored)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "eu.devsrv.samapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.devsrv.samapp"
        minSdk = 29
        targetSdk = 37
        versionCode = (property("appVersionCode") as String).toInt()
        versionName = property("appVersion") as String
        manifestPlaceholders["appLabel"] = "Football Tracker"
        // -PfootballDataKey= (empty) builds an APK without the key, which then uses the score server (workerUrl)
        val key = (findProperty("footballDataKey") as String?) ?: localProps.getProperty("footballDataKey", "")
        buildConfigField("String", "FOOTBALL_DATA_KEY", "\"$key\"")
        buildConfigField("String", "WORKER_URL", "\"${localProps.getProperty("workerUrl", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // Side-by-side install (own app id) with the long-press-Test event log. Build: ./gradlew assembleDiag
        create("diag") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".diag"
            manifestPlaceholders["appLabel"] = "Football Tracker DIAG"
        }
    }
}
