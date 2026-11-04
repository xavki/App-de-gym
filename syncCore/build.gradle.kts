// Núcleo de importación y sincronización de GymFlow: Kotlin/JVM puro (sin Android), para poder probarlo en la JVM.
// Lo usa :app (módulo Android) mediante `implementation project(':syncCore')`. Se puede construir por separado:
//   cd syncCore && gradle test
// Las versiones de los plugins las fija el build raíz (como subproyecto) o settings.gradle.kts (si se construye por separado).
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.gymflow"

// Bytecode para Java 17 (el mismo que usa el módulo Android), compilable con cualquier JDK >= 17
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Como subproyecto, los repositorios los define el settings raíz (FAIL_ON_PROJECT_REPOS); solo en modo independiente se declaran aquí.
if (rootProject == project) repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
