package claims.core

import claims.core.command.CommandAliasService
import claims.core.command.CommandRegistry
import claims.core.config.ClaimsConfig
import claims.core.locale.MessageService
import claims.core.module.ModuleRegistry
import claims.core.scheduler.PluginScheduler
import claims.core.storage.DatabaseManager
import claims.core.ui.ActionBarService
import claims.core.ui.BossBarService
import claims.core.ui.DialogKit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ClaimsContext(override val plugin: JavaPlugin) : CoreHost {

    @Volatile
    var config: ClaimsConfig = ClaimsConfig.load(plugin)
        private set

    override val messages = MessageService(plugin).apply { reload(config.language) }
    override val scheduler = PluginScheduler(plugin)
    val database =
        DatabaseManager(File(plugin.dataFolder, config.databaseFile), config.databasePoolSize, "Claims-SQLite")

    override val bossBar = BossBarService(scheduler) { config.bossBarEnabled }
    override val actionBar = ActionBarService(scheduler, { config.actionBarSettings() }, messages)
    val dialogs = DialogKit(messages)

    val modules = ModuleRegistry(plugin) { id, defaultEnabled -> config.isModuleEnabled(id, defaultEnabled) }
    override val commandRegistry = CommandRegistry()

    override val pluginDisplayName: String = "Claims"
    override val rootCommandName: String = "claims"
    override val rootAliases: Set<String> = setOf("cl")
    override val registerStandaloneAliases: Boolean get() = config.registerStandaloneAliases
    override val adminPermission: String = "claims.admin"
    override val customAliasPermissionPrefix: String = "claims.customalias."
    override val defaultCustomAliases: Map<String, List<String>> = linkedMapOf(
        "myclaims" to listOf("player: claims list"),
        "claimsettings" to listOf("player: claims settings"),
        "claimhelp" to listOf(
            "msg: <aqua><bold>Claims Guide:</bold></aqua> <gray>Use <white>/cl</white> for the dashboard or <white>/claim</white> to protect your land.</gray>"
        ),
    )

    val commandAliases = CommandAliasService(this)

    override fun reloadAll() {
        config = ClaimsConfig.load(plugin)
        messages.reload(config.language)
        actionBar.start()
        modules.reloadAll()
        commandAliases.reload()
    }

    fun shutdown() {
        commandAliases.shutdown()
        modules.disableAll()
        actionBar.stop()
        bossBar.clearAll()
        scheduler.shutdown()
        database.close()
    }
}
