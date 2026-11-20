package com.gymflow.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64

/**
 * Vinculación del panel web, como al emparejar una tele: la app (con sesión de
 * Firebase) pide un código corto; en el panel se escribe ese código y el servidor
 * lo cambia por un token de solo lectura. El código caduca en 10 minutos y sirve una vez.
 */
class PairingRepository(private val db: Database, private val clock: Clock) {

    private val random = SecureRandom()

    fun createCode(uid: String, email: String?): PairingCodeResponse = db.tx { c ->
        SyncRepository.ensureUser(c, uid, email)
        val now = clock.instant()
        // Limpieza de códigos caducados (de cualquiera) y de los anteriores de este usuario
        c.prepareStatement("DELETE FROM pairing_codes WHERE expires_at < ? OR user_id = ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now))
            st.setString(2, uid)
            st.executeUpdate()
        }
        val expires = now.plus(CODE_TTL)
        val code = generateSequence { randomCode() }.first { candidate ->
            c.prepareStatement("INSERT INTO pairing_codes (code, user_id, expires_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use { st ->
                st.setString(1, candidate)
                st.setString(2, uid)
                st.setTimestamp(3, Timestamp.from(expires))
                st.executeUpdate() == 1
            }
        }
        PairingCodeResponse(code = code.chunked(4).joinToString("-"), expiresAt = expires.toEpochMilli())
    }

    /** Devuelve un token nuevo si el código existe y no ha caducado (y lo consume), o null. */
    fun claim(rawCode: String, label: String?): String? = db.tx { c ->
        val code = normalize(rawCode)
        val row = c.prepareStatement("SELECT user_id, expires_at FROM pairing_codes WHERE code = ? FOR UPDATE").use { st ->
            st.setString(1, code)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getTimestamp(2).toInstant() else null }
        } ?: return@tx null
        c.prepareStatement("DELETE FROM pairing_codes WHERE code = ?").use { st ->
            st.setString(1, code)
            st.executeUpdate()
        }
        val (uid, expiresAt) = row
        if (expiresAt.isBefore(clock.instant())) return@tx null

        val bytes = ByteArray(32).also(random::nextBytes)
        val token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        c.prepareStatement("INSERT INTO panel_tokens (token_hash, user_id, label) VALUES (?, ?, ?)").use { st ->
            st.setString(1, sha256(token))
            st.setString(2, uid)
            st.setString(3, label?.take(100))
            st.executeUpdate()
        }
        token
    }

    /** uid dueño del token de panel, o null si no existe (o se revocó). */
    fun userForToken(token: String): String? {
        if (!token.startsWith(TOKEN_PREFIX)) return null
        return db.tx { c ->
            c.prepareStatement("UPDATE panel_tokens SET last_used_at = now() WHERE token_hash = ? RETURNING user_id").use { st ->
                st.setString(1, sha256(token))
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    fun revokeAll(uid: String): Int = db.tx { c ->
        c.prepareStatement("DELETE FROM panel_tokens WHERE user_id = ?").use { st ->
            st.setString(1, uid)
            st.executeUpdate()
        }
    }

    private fun randomCode() = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    companion object {
        const val TOKEN_PREFIX = "gfp_"
        private const val CODE_LENGTH = 8
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sin 0/O ni 1/I
        private val CODE_TTL: Duration = Duration.ofMinutes(10)

        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
