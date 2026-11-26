package claims.core

import claims.core.command.CommandRegistry
import claims.core.locale.MessageService
import claims.core.scheduler.PluginScheduler
import claims.core.ui.ActionBarService
import claims.core.ui.BossBarService
import org.bukkit.plugin.java.JavaPlugin

/**
 * Minimal plugin host contract consumed by reusable core services (`CommandAliasService`,
 * `SpecializedCommandExecutor`, `RootCommandExecutor`) without coupling core to feature modules.
 */
interface CoreHost {
    val plugin: JavaPlugin
    val scheduler: PluginScheduler
    val messages: MessageService
    val actionBar: ActionBarService
    val bossBar: BossBarService
    val commandRegistry: CommandRegistry

    val pluginDisplayName: String
    val rootCommandName: String
    val rootAliases: Set<String>
    val registerStandaloneAliases: Boolean
    val adminPermission: String
    val customAliasPermissionPrefix: String
    val defaultCustomAliases: Map<String, List<String>>

    fun reloadAll()
}
