// Solo para construir/probar syncCore por separado (cd syncCore && gradle test). Dentro del proyecto Android, el settings raíz lo incluye.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("plugin.serialization") version "2.0.21"
    }
}
rootProject.name = "syncCore"
