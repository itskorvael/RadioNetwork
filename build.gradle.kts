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

dependencies {
    // Paper 26.x (year-based versioning). Runtime requires Java 25.
    compileOnly("io.papermc.paper:paper-api:$paperApi")

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
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version, "apiVersion" to pluginApiVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("RadioNetwork")
    archiveClassifier.set("mc$mcVersion")
    // Bundle the runtime libraries (JLayer) into the plugin jar.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}
