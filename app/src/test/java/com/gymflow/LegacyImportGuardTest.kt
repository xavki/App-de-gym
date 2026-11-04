package com.gymflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H6: la importación de datos antiguos de Firestore nunca se ejecuta sola (guardas estáticas sobre producción). */
class LegacyImportGuardTest {
    private val dir = File("src/main/java/com/gymflow")
    private fun all() = dir.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun `el login y el arranque no leen datos de usuario de Firestore`() {
        val vm = File(dir, "GymFlowViewModel.kt").readText()
        val init = vm.substringAfter("init {").substringBefore("private fun loadAll")
        assertFalse("el listener de autenticación no debe importar", init.contains("FirestoreImporter.importUser"))
        assertFalse(init.contains("importLegacyFirestore"))
    }

    @Test fun `solo una funcion de accion explicita llama a la importacion y fabrica el consentimiento`() {
        val callers = all().filter { it.name != "FirestoreImporter.kt" && it.readText().contains("importUserDataWithConsent") }.map { it.name }
        assertEquals(listOf("GymFlowViewModel.kt"), callers)
        val grants = all().filter { it.name != "FirestoreImporter.kt" && it.readText().contains("LegacyConsent.grant") }
        assertEquals(1, grants.size)
        // y esa función solo se invoca desde la pantalla, tras el diálogo
        val users = all().filter { it.readText().contains("importLegacyFirestore()") && it.name != "GymFlowViewModel.kt" }.map { it.name }
        assertEquals(listOf("ImportScreen.kt"), users)
    }

    @Test fun `no queda la importacion automatica antigua`() {
        assertTrue(all().none { it.readText().contains("importUserDataIfNeeded") })
    }

    @Test fun `el importador lee todo antes de guardar y comprueba la cuenta`() {
        val src = File(dir, "data/FirestoreImporter.kt").readText()
        val body = src.substringAfter("suspend fun importUserDataWithConsent")
        assertTrue(body.indexOf("FASE 1") in 0 until body.indexOf("FASE 2"))
        assertTrue(body.indexOf("repo.saveCustomExercise") > body.indexOf("FASE 2"))
        assertTrue(body.contains("stillActive()"))
    }
}
