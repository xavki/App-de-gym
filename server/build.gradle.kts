plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.0.3" // versiones de Ktor alineadas + tarea buildFatJar
    application
}

group = "com.gymflow"
version = "0.1.0"

application {
    mainClass.set("com.gymflow.server.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-server-compression")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-default-headers")

    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation(kotlin("test-junit5"))
    // PostgreSQL real dentro de los tests (sin Docker)
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Servidor de desarrollo para pruebas de extremo a extremo (app en el emulador → este PC):
// PostgreSQL embebido + claves de prueba. Nunca se usa en producción.
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Arranca el servidor con PostgreSQL embebido y autenticación de prueba"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gymflow.server.DevServerKt")
    workingDir = rootDir
}
