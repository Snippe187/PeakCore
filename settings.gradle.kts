pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "peakcore"
include("core-protocol", "core-api", "core-plugin")

// Beispiel-/Test-Modul (kein Teil des Cores): meldet sich an, empfaengt und sendet Pakete
include("probe-module")
project(":probe-module").projectDir = file("examples/probe-module")
