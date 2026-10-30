plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories { mavenCentral() }

// Reine Java-Bibliothek ohne Bukkit: Server (Plugin) und Client (Fabric-Mod) nutzen dasselbe Format.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
