import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.9.3"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // The JMAP wire format, read as a tree rather than as generated classes: Stalwart's
    // replies vary by version and an unknown field should never stop a message rendering.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Parses and sanitises message HTML. Hostile input, so this is not hand rolled.
    implementation("org.jsoup:jsoup:1.21.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "org.rampart.MainKt"

        nativeDistributions {
            // Windows first, per docs/architecture.md, Linux next. Dmg is left out until
            // macOS is actually a target: it rejects a major version of 0.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Rampart"
            packageVersion = "0.1.0"
            description = "Mail and server administration for Stalwart"
            vendor = "Justin Willhite"
        }
    }
}
