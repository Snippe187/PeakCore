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
    implementation(project(":core-api"))

    testImplementation("org.yaml:snakeyaml:2.4") // prueft, dass plugin.yml gueltiges YAML ist (ein falsch gesetzter Doppelpunkt hat Paper das Plugin ablehnen lassen)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}

// Das Plugin-Jar enthaelt die API gleich mit: Module (Plugins mit "depend: [PeakCore]") sehen deren Klassen ueber den Classloader des Cores.
tasks.jar {
    archiveBaseName = "PeakCore"
    from(project(":core-api").sourceSets["main"].output)
    from(project(":core-protocol").sourceSets["main"].output)
}
