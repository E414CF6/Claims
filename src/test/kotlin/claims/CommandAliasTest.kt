package claims

import claims.core.command.AliasMatcher
import claims.core.command.CommandAliasEntry
import claims.core.command.CommandAliasType
import claims.core.command.CommandRegistry
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommandAliasTest {

    @Test
    fun `AliasMatcher resolves exact, prefix with dynamic vars, disabled, and wildcard aliases`() {
        val claimAlias = CommandAliasEntry(
            alias = "claim",
            commands = listOf("claims claim \$1-"),
            enabled = true,
            type = CommandAliasType.BASE,
            targetSubcommand = "claim"
        )
        val exactListAlias = CommandAliasEntry(
            alias = "clist",
            commands = listOf("claims list"),
            enabled = true,
            type = CommandAliasType.CUSTOM,
            exact = true
        )
        val disabledAlias = CommandAliasEntry(
            alias = "sc",
            commands = listOf("claims subdivide \$1-"),
            enabled = false,
            type = CommandAliasType.SUBBASE,
            targetSubcommand = "subdivide"
        )
        val wildcardAlias = CommandAliasEntry(
            alias = "myclaim ?",
            commands = listOf("claims claim \$1-"),
            enabled = true,
            type = CommandAliasType.CUSTOM
        )

        val map = linkedMapOf(
            "claim" to claimAlias,
            "clist" to exactListAlias,
            "sc" to disabledAlias,
            "myclaim ?" to wildcardAlias
        )

        assertEquals(claimAlias, AliasMatcher.findBestMatch("claim", map))
        assertEquals(claimAlias, AliasMatcher.findBestMatch("claim 10", map))
        assertEquals(exactListAlias, AliasMatcher.findBestMatch("clist", map))
        assertNull(AliasMatcher.findBestMatch("clist extra", map))
        assertNull(AliasMatcher.findBestMatch("sc", map))
        assertEquals(wildcardAlias, AliasMatcher.findBestMatch("myclaim 15", map))
        assertEquals(wildcardAlias, AliasMatcher.findBestMatch("myclaim", map))
    }

    @Test
    fun `AliasMatcher expands positional arguments, rest args, {args}, and {player}`() {
        val trustAlias = CommandAliasEntry(
            alias = "trust",
            commands = listOf("claims trust \$1 \$2", "msg:\$1 was granted {args} by {player}")
        )
        val expanded = AliasMatcher.expandCommands(trustAlias, "trust Alice build", "OwnerSteve")
        assertEquals(
            listOf(
                "claims trust Alice build",
                "msg:Alice was granted Alice build by OwnerSteve"
            ),
            expanded
        )
    }

    @Test
    fun `AliasMatcher extractRemainder handles case-insensitive and wildcard aliases`() {
        val wildcard = CommandAliasEntry(alias = "myclaim ?", commands = listOf("claims claim \$1-"))
        assertEquals("12", AliasMatcher.extractRemainder(wildcard, "MYCLAIM 12"))
        assertEquals("", AliasMatcher.extractRemainder(wildcard, "myclaim"))

        val exactPrefix = CommandAliasEntry(alias = "trust", commands = listOf("claims trust \$1-"))
        assertEquals("Alice build", AliasMatcher.extractRemainder(exactPrefix, "TRUST Alice build"))
    }

    @Test
    fun `CommandRegistry respects standaloneByDefault and resolves aliases case-insensitively`() {
        val registry = CommandRegistry()
        registry.registerSubcommand(
            name = "Reload",
            description = "Reload config",
            permission = "claims.admin",
            standaloneByDefault = false
        ) { _: CommandSender, _ -> true }

        registry.registerSubcommand(
            name = "Trust",
            description = "Trust player",
            permission = "claims.claim",
            aliases = listOf("tr")
        ) { _: CommandSender, _ -> true }

        assertFalse(registry.getSubcommand("reload")!!.standaloneByDefault)
        val byAlias = registry.getSubcommand("TR")
        assertNotNull(byAlias)
        assertTrue(byAlias!!.standaloneByDefault)
        assertEquals("trust", byAlias.name)
    }
}
