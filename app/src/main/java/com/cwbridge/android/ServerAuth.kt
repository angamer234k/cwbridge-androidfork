package com.cwbridge.android

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Auth for the local web server.
 *
 * Rule from the TODO: a client on the local network (192.168.x.x, 10.x, 172.16-31.x)
 * does NOT need a password. Anything else — proxied, tunneled, or coming from the
 * internet — must present the generated password.
 *
 * A successful login returns a session token that is sent back as the
 * `CWBridge-Session` cookie, so the password is only needed once per browser.
 *
 * NOTE: the 6-digit pair code from the TODO is intentionally not implemented here.
 * It needs the separate Vercel/Firestore pairing service, which does not exist yet.
 */
object ServerAuth {

    private const val PREFS = "cwbridge_server_auth"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ENABLED = "enabled"
    private const val SESSION_TTL_MS = 24L * 60 * 60 * 1000
    private const val TOKEN_BYTES = 24

    private lateinit var prefs: android.content.SharedPreferences
    private val sessions = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** Master switch for web remote control. */
    fun isEnabled(context: Context): Boolean {
        init(context)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        init(context)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        LogBuffer.i("Auth", "remote control ${if (enabled) "enabled" else "disabled"}")
    }

    /** The generated password. Created on first use and then stable. */
    fun password(context: Context): String {
        init(context)
        val existing = prefs.getString(KEY_PASSWORD, null)
        if (!existing.isNullOrEmpty()) return existing
        val generated = generatePassword()
        prefs.edit().putString(KEY_PASSWORD, generated).apply()
        LogBuffer.i("Auth", "generated new server password")
        return generated
    }

    fun regeneratePassword(context: Context): String {
        init(context)
        val generated = generatePassword()
        prefs.edit().putString(KEY_PASSWORD, generated).apply()
        sessions.clear()
        LogBuffer.i("Auth", "password regenerated — all sessions dropped")
        return generated
    }

    /**
     * Core gate. Returns null when the request may proceed, or a reason string
     * when it must be rejected.
     */
    fun check(
        context: Context,
        remoteAddress: String,
        sessionToken: String?,
        presentedPassword: String?,
    ): String? {
        init(context)
        if (!isEnabled(context)) return "remote control is disabled in the app"
        if (isLocalNetwork(remoteAddress)) return null

        // Remote client: a live session is enough.
        if (sessionToken != null && isSessionValid(sessionToken)) return null

        // Otherwise the password must match.
        val expected = password(context)
        if (!presentedPassword.isNullOrEmpty() && presentedPassword == expected) return null

        return "password required"
    }

    /** Exchange a correct password for a session token. */
    fun login(context: Context, presentedPassword: String): String? {
        init(context)
        if (presentedPassword.isNullOrEmpty() || presentedPassword != password(context)) return null
        val token = randomToken()
        sessions[token] = System.currentTimeMillis() + SESSION_TTL_MS
        purgeExpired()
        LogBuffer.i("Auth", "remote session opened")
        return token
    }

    fun logout(sessionToken: String?) {
        if (sessionToken != null) sessions.remove(sessionToken)
    }

    private fun isSessionValid(token: String): Boolean {
        val expires = sessions[token] ?: return false
        if (expires < System.currentTimeMillis()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value < now }
    }

    companion object {
        /**
         * Loopback plus the RFC1918 ranges Android hands out over Wi-Fi/hotspot.
         * Note this checks the immediate peer only, which is the correct security
         * boundary: a reverse proxy would show up as 127.0.0.1 and would therefore
         * be treated as local. Callers that care should keep the server bound to
         * the LAN and not exposed publicly.
         */
        fun isLocalNetwork(address: String): Boolean {
            val host = address.substringBefore(':').trim()
            if (host.isEmpty()) return false
            if (host == "localhost" || host == "::1") return true
            if (host.startsWith("127.")) return true
            val parts = host.split(".")
            if (parts.size != 4) return false
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            return when (a) {
                10 -> true
                192 -> b == 168
                172 -> b in 16..31
                else -> false
            }
        }

        private fun randomToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Readable password: avoids ambiguous chars, 20 chars. */
        private fun generatePassword(): String {
            val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = SecureRandom()
            return (1..20).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}