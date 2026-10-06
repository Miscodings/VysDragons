plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "8.3.3"
}

group = "com.vys"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// MythicMobs, ModelEngine and Nexo are compiled against the exact jars installed on
// the server (paid plugins, not redistributable). Pass the folder holding them with
// -PapiDir=/path/to/jars. See README.md.
val apiDir = providers.gradleProperty("apiDir").orNull
    ?: throw GradleException("Pass -PapiDir=/path/to/jars (see README.md).")

fun apiJar(name: String): File {
    val jar = file("$apiDir/$name")
    if (!jar.isFile) throw GradleException("Missing $jar (see README.md).")
    return jar
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    compileOnly(files(apiJar("MythicMobsPremium-5.13.1-SNAPSHOT.jar")))
    compileOnly(files(apiJar("ModelEngine-R4.1.1.jar")))
    compileOnly(files(apiJar("nexo-1.27.jar")))
    // Paper ships sqlite-jdbc on the server classpath.
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("kotlin", "com.vys.vysdragons.libs.kotlin")
}

tasks.jar { enabled = false }

tasks.build { dependsOn(tasks.shadowJar) }

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}
