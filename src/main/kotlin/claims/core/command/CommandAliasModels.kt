package claims.core.command

import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Classification of an alias entry managed by [CommandAliasService].
 */
enum class CommandAliasType {
    /** Primary standalone alias generated for a registered subcommand (e.g. `/claim`). */
    BASE,

    /** Secondary standalone alias generated from a subcommand's `aliases` list (e.g. `/tr`). */
    SUBBASE,

    /** User-defined custom alias or macro loaded from `aliases.yml`. */
    CUSTOM,
}

/**
 * Configuration and matching metadata for a single dynamic command alias.
 */
data class CommandAliasEntry(
    val alias: String,
    val commands: List<String>,
    val enabled: Boolean = true,
    val type: CommandAliasType = CommandAliasType.CUSTOM,
    val targetSubcommand: String? = null,
    val tabComplete: Boolean = true,
    val baseDisabled: Boolean = false,
    val requiresPerm: Boolean = false,
    val exact: Boolean = false,
    val addTabs: Boolean = false,
    val customTabs: List<String> = emptyList(),
) {
    /** Lowercase root command token (before the first space) registered in Bukkit's CommandMap. */
    val baseCommand: String
        get() = alias.trim().substringBefore(' ').lowercase(Locale.ROOT)

    /** Normalized permission suffix appended to `claims.customalias.` when [requiresPerm] is true. */
    val permissionKey: String
        get() = alias.trim().replace(" ", "").lowercase(Locale.ROOT)

    /** `true` if any target command line references `$1`, `$1-`, or `{args}`. */
    val containsDynamicVariables: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        commands.any { cmd ->
            DYNAMIC_VAR_PATTERN.matcher(cmd).find() || cmd.contains("{args}", ignoreCase = true)
        }
    }

    /** Leading static command tokens before any `$n`, `[opt]`, or `{args}` placeholder. */
    val cleanCommand: String
        get() {
            val first = commands.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return ""
            val tokens = first.split(' ')
            val kept = ArrayList<String>(tokens.size)
            for (token in tokens) {
                if (token.startsWith("$") || token.startsWith("[") || token.equals("{args}", ignoreCase = true)) {
                    break
                }
                kept += token
            }
            return kept.joinToString(" ")
        }

    companion object {
        /** Matches positional (`$1`) or trailing-rest (`$1-`) argument placeholders. */
        val DYNAMIC_VAR_PATTERN: Pattern = Pattern.compile("\\$((\\d{1,2})(-?))(?!\\d)")
    }
}

/**
 * Stateless matcher and template expander for dynamic command aliases.
 *
 * Matching precedence:
 * 1. Exact alias match (longest alias wins on ties).
 * 2. Prefix match when `exact == false` and the alias uses dynamic variables (`$1`, `$1-`, `{args}`).
 * 3. Wildcard aliases ending with `?` (e.g. `myclaim ?`).
 */
object AliasMatcher {

    private val SPECIALIZED_PREFIXES = listOf(
        "console:",
        "player:",
        "op:",
        "msg:",
        "message:",
        "broadcast:",
        "bc:",
        "actionbar:",
        "bossbar:",
        "sound:",
        "delay!",
        "delay:",
    )

    /** Returns `true` if [line] starts with a specialized macro prefix (e.g. `console:`, `msg:`, `delay:`). */
    fun isSpecializedCommandLine(line: String): Boolean {
        val trimmed = line.trim().lowercase(Locale.ROOT)
        return SPECIALIZED_PREFIXES.any { trimmed.startsWith(it) }
    }

    /** Returns `true` if the alias requires [SpecializedCommandExecutor] (multi-line or prefixed). */
    fun isSpecializedAlias(commands: List<String>): Boolean =
        commands.size != 1 || commands.firstOrNull()?.let { isSpecializedCommandLine(it) } == true

    /** Finds the highest-priority enabled [CommandAliasEntry] matching [rawCommand] (without leading `/`). */
    fun findBestMatch(rawCommand: String, aliases: Map<String, CommandAliasEntry>): CommandAliasEntry? {
        val normalized = rawCommand.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null

        var bestMatch: CommandAliasEntry? = null
        var bestLength = -1
        var bestExact = false

        for (entry in aliases.values) {
            if (!entry.enabled) continue
            val key = entry.alias.trim().lowercase(Locale.ROOT)
            if (key.isEmpty() || key.endsWith("?")) continue

            val isExactHit = normalized == key
            val isPrefixHit = if (entry.exact) {
                false
            } else {
                normalized.startsWith("$key ") && entry.containsDynamicVariables
            }

            if (isExactHit || isPrefixHit) {
                if (isExactHit && !bestExact) {
                    bestMatch = entry
                    bestLength = key.length
                    bestExact = true
                } else if (isExactHit == bestExact && key.length > bestLength) {
                    bestMatch = entry
                    bestLength = key.length
                }
            }
        }

        if (bestMatch != null) return bestMatch

        var wildcardMatch: CommandAliasEntry? = null
        var wildcardLen = -1
        for (entry in aliases.values) {
            if (!entry.enabled) continue
            val key = entry.alias.trim().lowercase(Locale.ROOT)
            if (!key.endsWith("?")) continue
            val base = key.removeSuffix("?").trim()
            val matches = if (base.isEmpty()) {
                true
            } else {
                normalized == base || normalized.startsWith("$base ")
            }
            if (matches && base.length > wildcardLen) {
                wildcardMatch = entry
                wildcardLen = base.length
            }
        }
        return wildcardMatch
    }

    /**
     * Extracts the argument remainder after [entry]'s alias prefix (case-insensitive, stripping
     * any trailing `?` wildcard marker).
     */
    fun extractRemainder(entry: CommandAliasEntry, rawCommand: String): String {
        val aliasPrefix = entry.alias.trim().let {
            if (it.endsWith("?")) it.removeSuffix("?").trim() else it
        }
        val trimmedRaw = rawCommand.trim()
        return if (aliasPrefix.isNotEmpty() && trimmedRaw.length > aliasPrefix.length &&
            trimmedRaw.startsWith(aliasPrefix, ignoreCase = true)
        ) {
            trimmedRaw.substring(aliasPrefix.length).trim()
        } else {
            ""
        }
    }

    /** Expands all command templates in [entry] using arguments from [rawCommand] and [senderName]. */
    fun expandCommands(entry: CommandAliasEntry, rawCommand: String, senderName: String): List<String> {
        val remainder = extractRemainder(entry, rawCommand)
        val argTokens = if (remainder.isEmpty()) emptyList() else remainder.split(Regex("\\s+"))

        return entry.commands.map { template ->
            expandLine(template, argTokens, remainder, senderName)
        }
    }

    private fun expandLine(
        template: String,
        argTokens: List<String>,
        remainder: String,
        senderName: String,
    ): String {
        var result = template
            .replace("{args}", remainder, ignoreCase = true)
            .replace("{player}", senderName, ignoreCase = true)

        val matcher = CommandAliasEntry.DYNAMIC_VAR_PATTERN.matcher(result)
        val sb = StringBuffer()
        while (matcher.find()) {
            val indexOneBased = matcher.group(2)?.toIntOrNull() ?: 0
            val rest = !matcher.group(3).isNullOrEmpty()
            val idx = indexOneBased - 1
            val replacement = if (idx < 0 || idx >= argTokens.size) {
                ""
            } else if (rest) {
                argTokens.subList(idx, argTokens.size).joinToString(" ")
            } else {
                argTokens[idx]
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        result = sb.toString()

        return result.trimEnd()
    }
}
