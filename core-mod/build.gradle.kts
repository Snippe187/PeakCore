plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

base { archivesName = "PeakCore" }

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()
    runs {
        // Dev-Client verbindet automatisch mit dem lokalen Testserver (../run/server)
        named("client") {
            programArgs("--quickPlayMultiplayer", "localhost:25565", "--username", "PeakDev")
            // Test-Modul "probe": meldet sich an, schickt nach dem Handshake ein "ping" und protokolliert die Antwort (nur im Dev-Client)
            vmArg("-Dpeakcore.probe=true")
        }
    }
    mods {
        register("peakcore") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Paketformat und Zustandsmaschine als eigene Jars in der Mod (Jar-in-Jar)
    implementation(project(":core-protocol"))
    implementation(project(":core-client"))
    "clientImplementation"(project(":core-protocol"))
    "clientImplementation"(project(":core-client"))
    include(project(":core-protocol"))
    include(project(":core-client"))
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}

tasks.withType<JavaCompile>().configureEach { options.release = 25 }
