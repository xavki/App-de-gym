package com.gymflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** R3-04: los esquemas 1 y 2 deben existir siempre; el 3 (histórico) y el 4 (actual), cuando estén en el repositorio, deben tener la identidad auditada. */
class SchemaFilesGuardTest {
    private val dir = File("schemas/com.gymflow.data.GymDatabase")
    private fun hash(v: Int) = Regex(""""identityHash"\s*:\s*"([0-9a-f]+)"""").find(File(dir, "$v.json").readText())!!.groupValues[1]

    @Test fun `esquemas 1 y 2 presentes`() { assertTrue(File(dir, "1.json").exists() && File(dir, "2.json").exists()) }

    @Test fun `esquema 3 historico tiene la identidad de la v3 distribuida`() {
        assumeTrue("3.json aún no incorporado (R3-04 pendiente)", File(dir, "3.json").exists())
        assertEquals("32a6e958badc613142b6b8920ec7611a", hash(3))
    }

    @Test fun `esquema 4 actual tiene la identidad auditada`() {
        assumeTrue("4.json aún no incorporado (R3-04 pendiente)", File(dir, "4.json").exists())
        assertEquals("d7996e1a59d6e18ca49dae537a03cc80", hash(4))
    }
}
