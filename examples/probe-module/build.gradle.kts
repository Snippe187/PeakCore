plugins {
    java
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_version")}")
    // Zur Laufzeit liefert PeakCore die API (depend: [PeakCore]); das Jar enthaelt sie deshalb nicht
    compileOnly(project(":core-api"))
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}

tasks.jar { archiveBaseName = "PeakCoreProbe" }
