import java.util.Properties

plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("eu.devsrv.samapp.desktop.Main")
    applicationName = "football-tracker"
}

// ./gradlew :desktop:run picks the API key up from local.properties; an installed copy asks for it on first start
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
tasks.named<JavaExec>("run") {
    environment("FOOTBALL_DATA_KEY", localProps.getProperty("footballDataKey", ""))
}

// workerUrl=https://....workers.dev in local.properties (or -PworkerUrl=...) is baked into the app, so users need no API key
tasks.processResources {
    val appVersion = project.property("appVersion") as String
    inputs.property("appVersion", appVersion)
    filesMatching("version.properties") { filter { it.replace("@VERSION@", appVersion) } }
    val workerUrl = (findProperty("workerUrl") as String?) ?: localProps.getProperty("workerUrl", "")
    inputs.property("workerUrl", workerUrl)
    filesMatching("server.properties") { filter { it.replace("@WORKER_URL@", workerUrl) } }
}
