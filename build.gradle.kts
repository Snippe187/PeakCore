// Gemeinsame Einstellungen aller PeakCore-Projekte: gleiche Gruppe und Version.
subprojects {
    group = property("maven_group") as String
    version = property("core_version") as String
}
