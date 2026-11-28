package claims.core.command

import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import java.util.*

/**
 * Dynamic [BukkitCommand] proxy registered into the server's `CommandMap` for standalone
 * subcommand aliases and custom aliases managed by [CommandAliasService].
 */
class AliasCommandProxy(
    aliasName: String,
    private val service: CommandAliasService,
    commandDescription: String,
) : BukkitCommand(aliasName.lowercase(Locale.ROOT)) {

    init {
        description = commandDescription
        usageMessage = "/$aliasName"
    }

    override fun testPermissionSilent(target: CommandSender): Boolean =
        service.canUseAlias(target, name)

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
        service.executeFromCommandMap(sender, commandLabel, args)

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> =
        service.tabCompleteAlias(sender, alias, args, null)

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<out String>,
        location: Location?,
    ): List<String> = service.tabCompleteAlias(sender, alias, args, location)
}
