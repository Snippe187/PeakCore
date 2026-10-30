plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories { mavenCentral() }

// Reine Java-Bibliothek ohne Minecraft: die Zustandsmaschine der Mod (Handshake, Modul-Routing), damit sie sich testen laesst.
dependencies {
    api(project(":core-protocol"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
