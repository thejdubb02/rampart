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

val javafxVersion = "21.0.5"
// web is the engine; swing is the bridge into a Compose window; the other three are what
// web itself requires.
val javafx = listOf("base", "graphics", "controls", "media", "swing", "web")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // The JMAP wire format, read as a tree rather than as generated classes: Stalwart's
    // replies vary by version and an unknown field should never stop a message rendering.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Parses and sanitises message HTML. Hostile input, so this is not hand rolled.
    implementation("org.jsoup:jsoup:1.21.1")

    /*
     * IMAP, so Rampart is not a client for one server.
     *
     * Angus Mail is the maintained implementation of Jakarta Mail, which is the reference
     * IMAP client on the JVM and has been since it was JavaMail. Not hand rolled: IMAP is
     * thirty years of extensions, folded headers, encodings and server quirks, and the
     * failure mode of getting one of them subtly wrong is mail that reads incorrectly
     * rather than an error anybody sees.
     */
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    // SQLite with FTS5 for the local store, and with encryption, which the stock xerial
    // driver does not have. This is that driver plus SQLCipher, so encrypting the file is
    // a pragma on the connection rather than a project of its own.
    implementation("io.github.willena:sqlite-jdbc:3.50.1.0")

    // Reaches Windows DPAPI, so a remembered password is encrypted by the operating
    // system against the logged in user rather than by anything we wrote.
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    /*
     * A real engine for message HTML.
     *
     * Rampart drew HTML itself until now: parse, reduce to a list of blocks, lay them out
     * in Compose. That works for a written message and cannot work for a designed one. A
     * marketing email is a nest of tables carrying inline CSS, media queries, background
     * images and widths, and matching it means implementing a browser. Every version of
     * the hand renderer was one CSS feature short of the next email.
     *
     * So the same answer webmail gets for free: sanitise, then hand it to an engine. Ours
     * is JavaFX's WebKit, which is the light one. Chromium through JCEF would be the other
     * choice and costs three times the download for accuracy no email needs.
     *
     * Only the `web` module is large (31 MB on Windows); the rest are a few MB together.
     */
    javafx.forEach { module ->
        // The classifier is the platform, and there is no neutral one. Linux is what this
        // box compiles and tests against; Windows is what actually ships.
        compileOnly("org.openjfx:javafx-$module:$javafxVersion:linux")
        testImplementation("org.openjfx:javafx-$module:$javafxVersion:linux")
        windowsAmd64("org.openjfx:javafx-$module:$javafxVersion:win")
        linuxAmd64("org.openjfx:javafx-$module:$javafxVersion:linux")
    }

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

/*
 * The changelog, written into the build rather than fetched.
 *
 * Every commit is a version, because the version is the commit count, and every commit
 * subject is already one plain sentence saying what changed: that is the house style and
 * it exists for this. So the log is the changelog and there is nothing to maintain by
 * hand, nothing to forget to update, and no second place for the two to disagree.
 *
 * Baked in as a resource rather than fetched from GitHub so it works offline and so it
 * always describes the build it is inside, rather than whatever has been released since.
 */
val changelogFile = layout.buildDirectory.file("generated/changelog/changelog.tsv")

val changelog by tasks.registering {
    outputs.file(changelogFile)
    // Never up to date: a new commit changes the answer and nothing else tells Gradle so.
    outputs.upToDateWhen { false }
    doLast {
        val out = changelogFile.get().asFile
        out.parentFile.mkdirs()
        val log = runCatching {
            ProcessBuilder("git", "log", "--reverse", "--pretty=format:%cs%x09%s")
                .directory(rootDir).start().inputStream.bufferedReader().readText()
        }.getOrDefault("")
        // Numbered from the first commit forward, so line N is version 0.1.N, then
        // reversed so the newest is first. Capped: nobody scrolls two hundred releases.
        val lines = log.lineSequence().filter { it.isNotBlank() }
            .mapIndexed { at, line -> "0.1.${at + 1}\t$line" }
            .toList().asReversed().take(200)
        out.writeText(lines.joinToString("\n"))
    }
}

sourceSets.main { resources.srcDir(changelogFile.map { it.asFile.parentFile }) }
tasks.named("processResources") { dependsOn(changelog) }

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
