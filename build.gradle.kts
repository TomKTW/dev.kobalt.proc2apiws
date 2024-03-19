plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.kobalt"
version = "0000.00.00.00.00.00.000"

repositories {
    mavenCentral()
}

fun ktor(module: String, version: String) = "io.ktor:ktor-$module:$version"
fun exposed(module: String, version: String) = "org.jetbrains.exposed:exposed-$module:$version"
fun general(module: String, version: String) = "$module:$version"
fun kotlinx(module: String, version: String) = "org.jetbrains.kotlinx:kotlinx-$module:$version"
fun kotlinw(module: String, version: String) = "org.jetbrains.kotlin-wrappers:kotlin-$module:$version"

fun DependencyHandler.httpServer() {
    implementation(ktor("server-cio", "2.3.7"))
    implementation(ktor("server-core", "2.3.7"))
    implementation(ktor("server-sessions", "2.3.7"))
    implementation(ktor("server-forwarded-header", "2.3.7"))
    implementation(ktor("server-default-headers", "2.3.7"))
    implementation(ktor("server-caching-headers", "2.3.7"))
    implementation(ktor("server-call-logging", "2.3.7"))
    implementation(ktor("server-compression", "2.3.7"))
    implementation(ktor("server-status-pages", "2.3.7"))
    implementation(ktor("server-html-builder", "2.3.7"))
}

fun DependencyHandler.commandLineInterface() {
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.3")
}

fun DependencyHandler.standardLibrary() {
    implementation(kotlin("stdlib", "1.9.22"))
}

fun DependencyHandler.logger() {
    implementation(general("org.slf4j:slf4j-simple", "1.7.36"))
}

fun DependencyHandler.htmlParser() {
    implementation(general("org.jsoup:jsoup", "1.14.3"))
}

fun DependencyHandler.htmlDsl() {
    implementation(kotlinx("html-jvm", "0.7.3"))
}

fun DependencyHandler.cssDsl() {
    implementation(kotlinw("css-jvm", "1.0.0-pre.298-kotlin-1.6.10"))
}

dependencies {
    standardLibrary()
    commandLineInterface()
    httpServer()
    htmlParser()
    htmlDsl()
    cssDsl()
    logger()
}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveFileName.set("proc2apiws.web.jar")
        mergeServiceFiles()
        manifest {
            attributes("Main-Class" to "dev.kobalt.proc2apiws.web.MainKt")
        }
    }
}