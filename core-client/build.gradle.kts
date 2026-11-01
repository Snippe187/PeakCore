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
    // Gson bringt Minecraft selbst mit; hier nur zum Uebersetzen und Testen
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
