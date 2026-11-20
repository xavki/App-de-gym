package com.gymflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invariantes de seguridad de los workflows de GitHub Actions (el módulo se ejecuta desde app/). */
class WorkflowSafetyTest {

    private val wfDir = File("../.github/workflows")
    private fun wf(name: String) = File(wfDir, name).readText()
    private val pinned = Regex("""uses:\s*\S+@[0-9a-f]{40}\b""")

    @Test fun `todas las acciones de todos los workflows estan fijadas a SHA completo`() {
        for (f in wfDir.listFiles { x -> x.extension == "yml" }!!) {
            val usos = f.readLines().filter { it.trim().startsWith("uses:") || it.contains("- uses:") }
            assertTrue("${f.name} sin acciones", usos.isNotEmpty())
            for (u in usos) assertTrue("${f.name}: acción sin SHA completo: $u", pinned.containsMatchIn(u))
        }
    }

    @Test fun `la validacion no usa secretos ni entornos ni firma ni publica APK`() {
        val v = wf("android-validate.yml").lines().filterNot { it.trim().startsWith("#") }.joinToString("\n")
        for (prohibido in listOf("secrets.", "environment:", "apksigner", "keystore", "DEBUG_KEYSTORE", "id-token", "contents: write")) {
            assertFalse("android-validate.yml contiene '$prohibido'", v.contains(prohibido, ignoreCase = true))
        }
        assertFalse("la validación no debe subir la APK", v.contains("app-debug.apk"))
        assertFalse(v.contains("  sign:"))
        assertTrue(v.contains("permissions: {}"))
        assertTrue("debe verificar el SHA del código", v.contains("git rev-parse HEAD"))
    }

    @Test fun `la validacion no puede lanzarse con secretos desde pull requests de terceros`() {
        val v = wf("android-validate.yml").lines().filterNot { it.trim().startsWith("#") }.joinToString("\n")
        assertFalse(v.contains("pull_request_target"))
        assertFalse(v.contains("workflow_run"))
    }

    @Test fun `la firma sigue aislada, desde master, en el entorno y con Build Tools fijado`() {
        val w = wf("android-debug-apk.yml")
        assertTrue(w.contains("environment: apk-signing"))
        assertTrue(w.contains("github.ref == 'refs/heads/master'"))
        assertTrue(w.contains("BUILD_TOOLS_VERSION: \"35.0.0\""))
        assertFalse("no debe elegir la última versión de Build Tools", w.contains("sort -V"))
        assertFalse(w.contains("ls \"\$ANDROID_HOME\"/build-tools"))
    }

    @Test fun `el secreto solo aparece en el paso de firma`() {
        val w = wf("android-debug-apk.yml").lines().filterNot { it.trim().startsWith("#") }
        val usos = w.filter { it.contains("secrets.") }
        assertTrue("el secreto debe usarse exactamente una vez: $usos", usos.size == 1)
        val signStart = w.indexOfFirst { it.startsWith("  sign:") }
        assertTrue(signStart > 0 && w.indexOf(usos[0]) > signStart)
    }
}
