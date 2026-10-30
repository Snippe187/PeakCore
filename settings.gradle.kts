pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "peakcore"
include("core-protocol", "core-api", "core-plugin", "core-client", "core-mod")

// Beispiel-/Test-Modul (kein Teil des Cores): meldet sich an, empfaengt und sendet Pakete
include("probe-module")
project(":probe-module").projectDir = file("examples/probe-module")
