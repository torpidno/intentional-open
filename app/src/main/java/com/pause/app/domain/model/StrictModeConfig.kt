package com.pause.app.domain.model

/**
 * Friction challenge presented before disabling Pause or weakening protections
 * when Strict Mode is enabled.
 */
enum class StrictModeChallenge {
    /** Requires waiting through a brief countdown before confirming. */
    DELAY,

    /** Requires typing a short confirmation phrase before confirming. */
    PHRASE,
}

/** Pure matching helpers for Strict Mode confirmation challenges. */
object StrictModePolicy {
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Returns true when [input] matches [expected] after trimming ends,
     * collapsing internal whitespace runs to a single space, and ignoring case.
     * A blank [expected] phrase never matches.
     */
    fun matchesPhrase(input: String, expected: String): Boolean {
        val normalizedExpected = normalize(expected)
        if (normalizedExpected.isEmpty()) return false
        return normalize(input).equals(normalizedExpected, ignoreCase = true)
    }

    private fun normalize(value: String): String =
        value.trim().replace(WHITESPACE_REGEX, " ")
}
