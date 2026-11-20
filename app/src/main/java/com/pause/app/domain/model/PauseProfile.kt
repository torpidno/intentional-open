package com.pause.app.domain.model

import com.pause.app.data.preferences.PauseSettings
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * A reusable configuration bundle of monitored apps, friction sequences, and
 * optional active-hours schedule.
 *
 * Profiles can be applied manually or matched automatically by their schedule
 * when [PauseSettings.autoSwitchProfiles] is enabled.
 */
data class PauseProfile(
    val id: String,
    val name: String,
    val monitoredPackages: Set<String> = emptySet(),
    val pausedPackages: Set<String> = emptySet(),
    val defaultFrictionModes: List<FrictionMode> = listOf(FrictionMode.BREATHING),
    val appModeOverrides: Map<String, List<FrictionMode>> = emptyMap(),
    val scheduleEnabled: Boolean = false,
    val scheduleDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val scheduleStartMinutes: Int = PauseSettings.DEFAULT_SCHEDULE_START_MINUTES,
    val scheduleEndMinutes: Int = PauseSettings.DEFAULT_SCHEDULE_END_MINUTES,
) {
    /** Monitored apps in this profile with temporarily paused apps excluded. */
    val effectiveMonitoredPackages: Set<String>
        get() = monitoredPackages - pausedPackages

    /**
     * Returns true when this profile's schedule is enabled and [now] (in local
     * time) falls inside its configured weekday and time window.
     *
     * Uses the same overnight-safe weekday semantics as [PauseSettings]:
     * a start equal to the end means all day on selected weekdays, and an
     * overnight window (`start > end`) belongs to the weekday it starts on,
     * checking [ZonedDateTime.getDayOfWeek] for minutes `>= start` and
     * `now.dayOfWeek.minus(1)` for minutes `< end`.
     */
    fun isScheduleActive(now: ZonedDateTime): Boolean {
        if (!scheduleEnabled) return false
        return isWithinScheduleWindow(
            now = now,
            scheduleDays = scheduleDays,
            scheduleStartMinutes = scheduleStartMinutes,
            scheduleEndMinutes = scheduleEndMinutes,
        )
    }

    companion object {
        private val WEEKDAYS = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
        private val WEEKEND_DAYS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        fun workPreset(
            id: String = "work",
            monitoredPackages: Set<String> = emptySet(),
            name: String = "Work",
        ): PauseProfile = PauseProfile(
            id = id,
            name = name,
            monitoredPackages = monitoredPackages,
            defaultFrictionModes = listOf(FrictionMode.BREATHING, FrictionMode.INTENTION),
            scheduleEnabled = true,
            scheduleDays = WEEKDAYS,
            scheduleStartMinutes = 9 * 60,
            scheduleEndMinutes = 17 * 60,
        )

        fun eveningPreset(
            id: String = "evening",
            monitoredPackages: Set<String> = emptySet(),
            name: String = "Evening",
        ): PauseProfile = PauseProfile(
            id = id,
            name = name,
            monitoredPackages = monitoredPackages,
            defaultFrictionModes = listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            scheduleEnabled = true,
            scheduleDays = DayOfWeek.entries.toSet(),
            scheduleStartMinutes = 20 * 60,
            scheduleEndMinutes = 23 * 60 + 30,
        )

        fun weekendPreset(
            id: String = "weekend",
            monitoredPackages: Set<String> = emptySet(),
            name: String = "Weekend",
        ): PauseProfile = PauseProfile(
            id = id,
            name = name,
            monitoredPackages = monitoredPackages,
            defaultFrictionModes = listOf(FrictionMode.BREATHING),
            scheduleEnabled = true,
            scheduleDays = WEEKEND_DAYS,
            scheduleStartMinutes = 9 * 60,
            scheduleEndMinutes = 22 * 60,
        )

        fun encodeProfiles(profiles: List<PauseProfile>): String =
            ProfileJsonCodec.encode(profiles)

        fun decodeProfiles(raw: String): List<PauseProfile> =
            ProfileJsonCodec.decode(raw)
    }
}

/**
 * Shared overnight-safe weekday schedule evaluation used by both
 * [PauseSettings] and [PauseProfile].
 */
internal fun isWithinScheduleWindow(
    now: ZonedDateTime,
    scheduleDays: Set<DayOfWeek>,
    scheduleStartMinutes: Int,
    scheduleEndMinutes: Int,
): Boolean {
    if (scheduleDays.isEmpty()) return false

    val minuteOfDay = now.hour * 60 + now.minute
    val start = scheduleStartMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1)
    val end = scheduleEndMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1)
    return when {
        start == end -> now.dayOfWeek in scheduleDays
        start < end -> now.dayOfWeek in scheduleDays && minuteOfDay in start until end
        minuteOfDay >= start -> now.dayOfWeek in scheduleDays
        minuteOfDay < end -> now.dayOfWeek.minus(1) in scheduleDays
        else -> false
    }
}

/** Encodes [profiles] to a standard JSON array string with zero Android dependencies. */
fun encodeProfiles(profiles: List<PauseProfile>): String =
    ProfileJsonCodec.encode(profiles)

/**
 * Decodes a JSON array string produced by [encodeProfiles].
 * Malformed JSON or invalid individual entries are ignored so corrupt data
 * never crashes the app or background service.
 */
fun decodeProfiles(raw: String): List<PauseProfile> =
    ProfileJsonCodec.decode(raw)

/**
 * Minimal pure-Kotlin RFC-8259 JSON serializer/deserializer for [PauseProfile].
 *
 * Avoids Android's `org.json` stubs so plain-JVM unit tests work identically to
 * Android runtime without extra dependencies.
 */
private object ProfileJsonCodec {
    private const val MAX_NESTING_DEPTH = 32

    fun encode(profiles: List<PauseProfile>): String {
        val sb = StringBuilder()
        sb.append('[')
        profiles.forEachIndexed { index, profile ->
            if (index > 0) sb.append(',')
            appendProfile(sb, profile)
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(raw: String): List<PauseProfile> {
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching { JsonReader(raw).parseRoot() }.getOrNull() as? List<*>
            ?: return emptyList()

        val seenIds = HashSet<String>()
        val result = ArrayList<PauseProfile>(parsed.size)
        for (item in parsed) {
            val map = item as? Map<*, *> ?: continue
            val profile = mapToProfile(map) ?: continue
            if (seenIds.add(profile.id)) {
                result += profile
            }
        }
        return result
    }

    private fun appendProfile(sb: StringBuilder, profile: PauseProfile) {
        val modes = profile.defaultFrictionModes
            .distinct()
            .ifEmpty { listOf(FrictionMode.BREATHING) }
        val overrides = profile.appModeOverrides.entries
            .mapNotNull { (pkg, list) ->
                val clean = list.distinct()
                if (pkg.isBlank() || clean.isEmpty()) null else pkg to clean
            }
            .sortedBy { it.first }

        sb.append('{')
        appendField(sb, "id") { appendJsonString(sb, profile.id) }
        sb.append(',')
        appendField(sb, "name") { appendJsonString(sb, profile.name) }
        sb.append(',')
        appendField(sb, "monitoredPackages") {
            appendStringArray(sb, profile.monitoredPackages.filter { it.isNotBlank() }.sorted())
        }
        sb.append(',')
        appendField(sb, "pausedPackages") {
            appendStringArray(sb, profile.pausedPackages.filter { it.isNotBlank() }.sorted())
        }
        sb.append(',')
        appendField(sb, "defaultFrictionModes") {
            appendStringArray(sb, modes.map { it.name })
        }
        sb.append(',')
        appendField(sb, "appModeOverrides") {
            sb.append('{')
            overrides.forEachIndexed { idx, (pkg, overrideModes) ->
                if (idx > 0) sb.append(',')
                appendJsonString(sb, pkg)
                sb.append(':')
                appendStringArray(sb, overrideModes.map { it.name })
            }
            sb.append('}')
        }
        sb.append(',')
        appendField(sb, "scheduleEnabled") { sb.append(profile.scheduleEnabled) }
        sb.append(',')
        appendField(sb, "scheduleDays") {
            appendStringArray(sb, profile.scheduleDays.sortedBy { it.ordinal }.map { it.name })
        }
        sb.append(',')
        appendField(sb, "scheduleStartMinutes") {
            sb.append(profile.scheduleStartMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1))
        }
        sb.append(',')
        appendField(sb, "scheduleEndMinutes") {
            sb.append(profile.scheduleEndMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1))
        }
        sb.append('}')
    }

    private inline fun appendField(sb: StringBuilder, key: String, writeValue: () -> Unit) {
        appendJsonString(sb, key)
        sb.append(':')
        writeValue()
    }

    private fun appendStringArray(sb: StringBuilder, values: List<String>) {
        sb.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            appendJsonString(sb, value)
        }
        sb.append(']')
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private fun mapToProfile(map: Map<*, *>): PauseProfile? {
        val id = (map["id"] as? String)?.trim().orEmpty()
        val name = (map["name"] as? String)?.trim().orEmpty()
        if (id.isEmpty() || name.isEmpty()) return null

        val monitoredPackages = parseStringSet(map["monitoredPackages"])
        val pausedPackages = parseStringSet(map["pausedPackages"])
        val defaultModes = parseFrictionModes(map["defaultFrictionModes"])
            .ifEmpty { listOf(FrictionMode.BREATHING) }

        val appOverrides = buildMap {
            val rawOverrides = map["appModeOverrides"] as? Map<*, *> ?: return@buildMap
            for ((key, value) in rawOverrides) {
                val pkg = (key as? String)?.trim().orEmpty()
                if (pkg.isEmpty()) continue
                val modes = parseFrictionModes(value)
                if (modes.isNotEmpty()) {
                    put(pkg, modes)
                }
            }
        }

        val scheduleEnabled = (map["scheduleEnabled"] as? Boolean) ?: false
        val scheduleDays = parseScheduleDays(map["scheduleDays"])
        val scheduleStart = (map["scheduleStartMinutes"] as? Number)?.toInt()
            ?.takeIf { it in 0 until PauseSettings.MINUTES_PER_DAY }
            ?: PauseSettings.DEFAULT_SCHEDULE_START_MINUTES
        val scheduleEnd = (map["scheduleEndMinutes"] as? Number)?.toInt()
            ?.takeIf { it in 0 until PauseSettings.MINUTES_PER_DAY }
            ?: PauseSettings.DEFAULT_SCHEDULE_END_MINUTES

        return PauseProfile(
            id = id,
            name = name,
            monitoredPackages = monitoredPackages,
            pausedPackages = pausedPackages,
            defaultFrictionModes = defaultModes,
            appModeOverrides = appOverrides,
            scheduleEnabled = scheduleEnabled,
            scheduleDays = scheduleDays,
            scheduleStartMinutes = scheduleStart,
            scheduleEndMinutes = scheduleEnd,
        )
    }

    private fun parseStringSet(raw: Any?): Set<String> {
        val list = raw as? List<*> ?: return emptySet()
        return list.mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }.toSet()
    }

    private fun parseFrictionModes(raw: Any?): List<FrictionMode> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            (entry as? String)?.let { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
        }.distinct()
    }

    private fun parseScheduleDays(raw: Any?): Set<DayOfWeek> {
        val list = raw as? List<*> ?: return DayOfWeek.entries.toSet()
        if (list.isEmpty()) return emptySet()
        val parsed = list.mapNotNull { entry ->
            (entry as? String)?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
        }.toSet()
        return parsed.ifEmpty { DayOfWeek.entries.toSet() }
    }

    private class JsonReader(private val input: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (pos != input.length) error("Trailing characters at $pos")
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_NESTING_DEPTH) error("Maximum JSON depth exceeded")
            skipWhitespace()
            if (pos >= input.length) error("Unexpected end of input")
            return when (val ch = input[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected character '$ch' at $pos")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list += parseValue(depth)
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < input.length) {
                val ch = input[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= input.length) error("Unterminated escape at $pos")
                        when (val esc = input[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) error("Invalid unicode escape at $pos")
                                val hex = input.substring(pos, pos + 4)
                                val codePoint = hex.toIntOrNull(16)
                                    ?: error("Invalid hex escape '$hex'")
                                sb.append(codePoint.toChar())
                                pos += 4
                            }
                            else -> error("Invalid escape '\\$esc' at $pos")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) error("Unescaped control char at $pos")
                        sb.append(ch)
                    }
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            if (pos >= input.length) error("Invalid number at $start")
            if (input[pos] == '0') {
                pos++
            } else if (input[pos] in '1'..'9') {
                while (pos < input.length && input[pos] in '0'..'9') pos++
            } else {
                error("Invalid number at $pos")
            }
            var isFloating = false
            if (peek('.')) {
                isFloating = true
                pos++
                val fracStart = pos
                while (pos < input.length && input[pos] in '0'..'9') pos++
                if (pos == fracStart) error("Missing fractional digits at $pos")
            }
            if (peek('e') || peek('E')) {
                isFloating = true
                pos++
                if (peek('+') || peek('-')) pos++
                val expStart = pos
                while (pos < input.length && input[pos] in '0'..'9') pos++
                if (pos == expStart) error("Missing exponent digits at $pos")
            }
            val token = input.substring(start, pos)
            return if (isFloating) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!input.startsWith(literal, pos)) error("Expected '$literal' at $pos")
            pos += literal.length
            return value
        }

        private fun expect(expected: Char) {
            if (pos >= input.length || input[pos] != expected) {
                error("Expected '$expected' at $pos")
            }
            pos++
        }

        private fun peek(ch: Char): Boolean = pos < input.length && input[pos] == ch

        private fun skipWhitespace() {
            while (pos < input.length && input[pos] in " \t\r\n") {
                pos++
            }
        }
    }
}
