import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.9.3"
    // Cross-builds the Windows package from this Linux box, and makes it self-updating.
    id("dev.hydraulic.conveyor") version "2.0"
}

// The patch number is the commit count, so every push is a newer version than the one
// installed and there is no bump to remember. Falls back to 0 outside a checkout.
val commits = runCatching {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir).start().inputStream.bufferedReader().readText().trim().toInt()
}.getOrDefault(0)
group = "io.github.thejdubb02"
version = "0.1.$commits"

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
    // Reaches Windows DPAPI, so a remembered password is encrypted by the operating
    // system against the logged in user rather than by anything we wrote.
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    // Skiko ships a different native library per platform, so packaging for Windows from
    // Linux means naming all of them rather than relying on compose.desktop.currentOs.
    linuxAmd64(compose.desktop.linux_x64)
    windowsAmd64(compose.desktop.windows_x64)
    macAmd64(compose.desktop.macos_x64)
    macAarch64(compose.desktop.macos_arm64)

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
            packageVersion = project.version.toString()
            description = "Mail and server administration for Stalwart"
            vendor = "Justin Willhite"
        }
    }
}
