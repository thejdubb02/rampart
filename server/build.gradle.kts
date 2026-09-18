plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories { mavenCentral() }

dependencies {
    // The only one. Everything else is the JDK: com.sun.net.httpserver serves the three
    // routes and java.security does the token comparison. A framework here would be a
    // larger dependency than the program it was serving.
    implementation("io.github.willena:sqlite-jdbc:3.50.1.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("org.rampart.tracker.TrackerKt") }

tasks.test { useJUnitPlatform() }

// A single jar the Dockerfile can run, so the image is a JRE and one file.
tasks.jar {
    manifest { attributes["Main-Class"] = "org.rampart.tracker.TrackerKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Signature files from a signed dependency do not survive being repacked, and the JVM
    // refuses to load a jar whose signatures no longer match what is in it.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
