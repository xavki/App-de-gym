package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecsTest {
    @Test fun `un conflicto sobrevive a guardarlo y leerlo`() {
        val r = routineRecs(1).first()
        val g = ConflictGroup(
            r.key, "Rutina 1",
            listOf(
                ConflictItem(r.table, r.id, r.copy(rev = 3, dirty = true), RemoteRecord(r.table, r.id, 4, 1000, null, r.data, 77)),
                ConflictItem("routine_exercises", uuid("solo-local"), r.copy(deletedAt = 5), null),
                ConflictItem("routine_exercises", uuid("solo-remoto"), null, RemoteRecord("routine_exercises", uuid("solo-remoto"), 2, 1, 9, r.data, 78)),
            ),
            ConflictReason.DELETED_REMOTELY, 1234, complete = true,
        )
        assertEquals(g, ConflictCodec.decode(ConflictCodec.encode(g)))
    }

    @Test fun `la direccion del servidor exige https y rechaza lo ambiguo`() {
        assertEquals("https://gym.example.com", ServerUrl.validate(" HTTPS://Gym.Example.com/ ").url)
        assertEquals("https://gym.example.com:8443", ServerUrl.validate("https://gym.example.com:8443").url)
        assertEquals("", ServerUrl.validate("   ").url)                                   // vacío = desactivada
        for (bad in listOf("http://gym.example.com", "gym.example.com", "ftp://x.com", "https://user:pw@x.com", "https://x.com/api", "https://x.com?a=1",
            "https://x.com#f", "https://", "https://x.com:99999", "javascript:alert(1)", "https://" + "a".repeat(250) + ".com"))
            assertFalse(ServerUrl.validate(bad).ok, bad)
    }

    @Test fun `http solo es posible hacia el equipo local y solo cuando se permite`() {
        assertEquals("http://10.0.2.2:8080", ServerUrl.validate("http://10.0.2.2:8080", allowInsecureLocal = true).url)
        assertEquals("http://localhost:8080", ServerUrl.validate("http://localhost:8080", allowInsecureLocal = true).url)
        assertNull(ServerUrl.validate("http://10.0.2.2:8080", allowInsecureLocal = false).url)       // en producción, nunca
        assertNull(ServerUrl.validate("http://evil.com", allowInsecureLocal = true).url)
        assertNull(ServerUrl.validate("http://10.0.2.2.evil.com", allowInsecureLocal = true).url)
    }
}
