package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** R4-03: la clave de caché de Inicio/Progreso (la revisión) cambia con el CONTENIDO, no con el tamaño de la lista. */
class ListPublisherTest {
    private data class W(val id: String, val name: String, val weight: Double, val date: Long)
    private val base = listOf(W("1", "Push", 60.0, 100), W("2", "Pull", 50.0, 200))

    private fun published(): Triple<ListPublisher<W>, MutableList<W>, Long> {
        val p = ListPublisher<W>(); val list = mutableListOf<W>()
        assertTrue(p.publish(list, p.begin(), "A", "A", base))
        return Triple(p, list, p.revision)
    }

    private fun assertChanges(modified: List<W>) {
        val (p, list, rev0) = published()
        assertEquals(base.size, modified.size)                         // misma cardinalidad: el tamaño NO sirve como clave
        assertTrue(p.publish(list, p.begin(), "A", "A", modified))
        assertNotEquals(rev0, p.revision, "la revisión debe cambiar"); assertEquals(modified, list)
    }

    @Test fun `cambio de contenido con igual numero de entrenos sube la revision`() = assertChanges(listOf(base[0], base[1].copy(name = "Pull B")))
    @Test fun `cambio de peso sube la revision`() = assertChanges(listOf(base[0].copy(weight = 62.5), base[1]))
    @Test fun `cambio de fecha sube la revision`() = assertChanges(listOf(base[0], base[1].copy(date = 999)))
    @Test fun `cambio de nombre sube la revision`() = assertChanges(listOf(base[0].copy(name = "Legs"), base[1]))

    @Test fun `recargar el mismo contenido no toca la lista ni la revision`() {
        val (p, list, rev0) = published()
        val before = list.toList()
        assertTrue(p.publish(list, p.begin(), "A", "A", base.map { it.copy() }))   // copias iguales
        assertEquals(rev0, p.revision); assertEquals(before, list)
    }

    @Test fun `cambio de cuenta durante la recarga no publica ni sube la revision`() {
        val (p, list, rev0) = published()
        val t = p.begin()
        assertFalse(p.publish(list, t, "A", "B", listOf(W("9", "De A", 1.0, 1))))
        assertEquals(rev0, p.revision); assertEquals(base, list)
    }

    @Test fun `clear al cerrar sesion vacia, invalida las recargas en vuelo y sube la revision`() {
        val (p, list, rev0) = published()
        val inFlight = p.begin()
        p.clear(list)
        assertTrue(list.isEmpty()); assertNotEquals(rev0, p.revision)
        assertFalse(p.publish(list, inFlight, "A", "A", base), "la recarga de la cuenta anterior no repuebla")
        assertTrue(list.isEmpty())
    }

    @Test fun `dos recargas solapadas publica solo la mas reciente aunque la antigua termine despues`() {
        val (p, list, _) = published()
        val old = p.begin(); val new = p.begin()
        val newer = listOf(base[0].copy(weight = 70.0), base[1])
        assertTrue(p.publish(list, new, "A", "A", newer))
        val rev = p.revision
        assertFalse(p.publish(list, old, "A", "A", base), "la antigua llega tarde y se descarta")
        assertEquals(newer, list); assertEquals(rev, p.revision)
    }
}
