package hr.gd.androidfilewifi.shared

/**
 * Turns untrusted names/paths coming from the phone into safe Windows path segments.
 * No ".." , no absolute paths, no drive letters, no characters Windows rejects, no reserved names.
 */
object PathSanitizer {
    private val illegal = Regex("""[<>:"/\\|?*\u0000-\u001F]""")
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Sanitizes a single path segment. Never returns an empty string, "." or "..". */
    fun segment(raw: String, fallback: String = "_"): String {
        var s = illegal.replace(raw, "_").trim().trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") return fallback
        val stem = s.substringBefore('.').uppercase()
        if (stem in reserved) s = "_$s"
        return if (s.length > 120) s.take(120).trimEnd('.', ' ') else s
    }

    /** Splits a relative path on / or \ and sanitizes each segment; drops empty and dot segments. */
    fun segments(relPath: String): List<String> {
        val parts = relPath.split('/', '\\')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .map { segment(it) }
        return parts.ifEmpty { listOf("_") }
    }
}
