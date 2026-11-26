package claims

import claims.core.ClaimsContext
import claims.core.command.RootCommandExecutor
import claims.core.compat.PlatformCompat
import claims.core.ui.MenuListener
import claims.modules.claim.ClaimModule
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class Claims : JavaPlugin() {

    var context: ClaimsContext? = null
        private set

    override fun onEnable() {
        instance = this
        dataFolder.mkdirs()

        val ctx = ClaimsContext(this)
        context = ctx
        Bukkit.getServicesManager().register(ClaimsContext::class.java, ctx, this, ServicePriority.Normal)

        val claimModule = ClaimModule(ctx)

        val pm = server.pluginManager
        pm.registerEvents(MenuListener(), this)
        pm.registerEvents(ctx.commandAliases, this)
        ctx.actionBar.start()

        ctx.modules.register(claimModule)
        ctx.modules.enableAll()

        ctx.commandRegistry.clear()
        val rootExecutor = RootCommandExecutor(
            host = ctx,
            commandAliases = ctx.commandAliases,
            userPermission = "claims.use",
            openDashboard = { player -> claimModule.menus.openDashboard(player) },
            openAdminPanel = { player -> claimModule.menus.openAdminPanel(player) },
            statusReporter = { sender ->
                val count = claimModule.claimManager.getAllTopLevelClaims().size
                ctx.messages.sendRaw(
                    sender,
                    "<aqua>Claims Status</aqua> <gray>|</gray> Top-level Claims: <white>$count</white> " +
                            "<gray>|</gray> Modules: <white>${ctx.modules.activeModules().size}</white> " +
                            "<gray>|</gray> Aliases: <white>${ctx.commandAliases.getAllAliases().size}</white>"
                )
            },
        )
        rootExecutor.registerCoreSubcommands()
        ctx.modules.registerAllSubcommands(ctx.commandRegistry)

        getCommand("claims")?.let {
            it.setExecutor(rootExecutor)
            it.tabCompleter = rootExecutor
        }

        ctx.commandAliases.initializeFromRegistry()

        val uiMode = if (PlatformCompat.supportsDialogs) "Native Paper Dialogs" else "Legacy GUI Fallback"
        logger.info(
            "Claims enabled on MC ${PlatformCompat.serverVersionLabel()} ($uiMode)! " +
                    "Modules: ${ctx.modules.activeModules().size}, Aliases: ${ctx.commandAliases.getAllAliases().size}"
        )
    }

    override fun onDisable() {
        context?.shutdown()
        runCatching { Bukkit.getServicesManager().unregisterAll(this) }
        context = null
        instance = null
    }

    companion object {
        @Volatile
        var instance: Claims? = null
            private set
    }
}
