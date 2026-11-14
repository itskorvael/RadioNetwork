plugins {
    `java-library`
}

group = property("group") as String
version = property("version") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://maven.maxhenkel.de/repository/public") {
        name = "maxhenkel"
    }
}

// Target Minecraft version; override with e.g. -PmcVersion=26.1.2 -PpaperApi=26.1.2.build.74-stable -PapiVersion=26.1
val mcVersion = (findProperty("mcVersion") as String?) ?: "26.2"
val paperApi = (findProperty("paperApi") as String?) ?: "26.2.build.134-stable"
val pluginApiVersion = (findProperty("apiVersion") as String?) ?: "26.2"
// The dialog screens (Paper 1.21.6+) are built apart, against a Paper that has them, so the rest of the plugin can be
// built against the oldest Paper it supports. For 26.x builds both are the same API.
val dialogApi = (findProperty("dialogApi") as String?) ?: paperApi
// Java level to compile for: 25 for Paper 26.x; use -PjavaRelease=21 for Paper 1.21.x
val javaRelease = ((findProperty("javaRelease") as String?) ?: "25").toInt()

sourceSets {
    // DialogPrompt: needs Paper's dialog API, which Paper before 1.21.6 does not have. Loaded only when it exists.
    create("dialog") {
        java.srcDir("src/dialog/java")
        compileClasspath += sourceSets.main.get().output
    }
}

dependencies {
    // Paper 26.x (year-based versioning) needs Java 25; Paper 1.21.x needs Java 21.
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    // the dialog classes build against their own (newer) Paper API, not the one the main classes use
    add("dialogCompileOnly", "io.papermc.paper:paper-api:$dialogApi")
    add("dialogCompileOnly", "de.maxhenkel.voicechat:voicechat-api:2.6.0")

    // Simple Voice Chat API (soft dependency; the plugin runs text-only without the mod/plugin).
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")

    // Pure-Java MP3 decoder, bundled into the jar: used when voice chat's own (native LAME) decoder is unavailable.
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaRelease)
}

tasks.processResources {
    val props = mapOf("version" to project.version, "apiVersion" to pluginApiVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.named("compileDialogJava") {
    dependsOn(tasks.named("compileJava"))
}

tasks.jar {
    archiveBaseName.set("RadioNetwork")
    from(sourceSets["dialog"].output)
    archiveClassifier.set("mc$mcVersion")
    // Bundle the runtime libraries (JLayer) into the plugin jar.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}
