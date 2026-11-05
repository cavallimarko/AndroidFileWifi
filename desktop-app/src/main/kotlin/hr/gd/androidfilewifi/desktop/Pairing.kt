package hr.gd.androidfilewifi.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Holds the current 6-digit pairing code. A code is single-use, and five wrong guesses
 * replace it, so it cannot be brute-forced.
 */
class PairingManager(private val random: SecureRandom = SecureRandom()) {
    private val _code = MutableStateFlow(newCode())
    val code: StateFlow<String> = _code
    private var failures = 0

    private fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    @Synchronized
    fun regenerate() {
        failures = 0
        _code.value = newCode()
    }

    @Synchronized
    fun verify(candidate: String): Boolean {
        val ok = MessageDigest.isEqual(candidate.trim().toByteArray(), _code.value.toByteArray())
        if (ok) {
            regenerate()
        } else if (++failures >= MAX_FAILURES) {
            regenerate()
        }
        return ok
    }

    fun newToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val MAX_FAILURES = 5
        fun hashToken(token: String): String = Identity.sha256Hex(token.toByteArray())
    }
}
