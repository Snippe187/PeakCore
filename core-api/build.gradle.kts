plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Module-ID-Regeln und Paketformat gehoeren zur API (Module sehen sie transitiv)
    api(project(":core-protocol"))
    // Die API haengt nur an der Bukkit-Schnittstelle (Plugin, ServicesManager); zur Laufzeit liefert sie der Server
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_version")}")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
