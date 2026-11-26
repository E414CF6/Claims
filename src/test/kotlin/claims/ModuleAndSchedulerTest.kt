package claims

import claims.core.command.CommandRegistry
import claims.core.module.FeatureModule
import claims.core.module.ModuleRegistry
import claims.core.scheduler.PluginScheduler
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.logging.Logger

class ModuleAndSchedulerTest {

    @Test
    fun `ModuleRegistry honors config module flags, subcommand registration, reload, and reverse disable`() {
        val lifecycleEvents = mutableListOf<String>()
        val flags = mapOf("claim" to true, "extra" to false)
        val registry = ModuleRegistry(proxyPlugin()) { id, def -> flags[id] ?: def }
        val commandRegistry = CommandRegistry()

        val claimModule = object : FeatureModule {
            override val id = "claim"
            override val description = "Claim module"
            override fun onEnable() {
                lifecycleEvents += "enable:claim"
            }

            override fun onReload() {
                lifecycleEvents += "reload:claim"
            }

            override fun onDisable() {
                lifecycleEvents += "disable:claim"
            }

            override fun registerSubcommands(registry: CommandRegistry) {
                registry.registerSubcommand("claim", "Create claim", "claims.claim") { _, _ -> true }
            }
        }

        val disabledModule = object : FeatureModule {
            override val id = "extra"
            override val description = "Disabled module"
            override fun onEnable() {
                lifecycleEvents += "enable:extra"
            }

            override fun registerSubcommands(registry: CommandRegistry) {
                registry.registerSubcommand("extra", "Extra", "claims.admin") { _, _ -> true }
            }
        }

        val addonModule = object : FeatureModule {
            override val id = "addon"
            override val description = "Addon module"
            override fun onEnable() {
                lifecycleEvents += "enable:addon"
            }

            override fun onDisable() {
                lifecycleEvents += "disable:addon"
            }
        }

        registry.register(claimModule)
        registry.register(disabledModule)
        registry.register(addonModule)

        registry.enableAll()
        assertTrue(registry.isActive("claim"))
        assertFalse(registry.isActive("extra"))
        assertTrue(registry.isActive("addon"))

        registry.registerAllSubcommands(commandRegistry)
        assertNotNull(commandRegistry.getSubcommand("claim"))
        assertEquals(null, commandRegistry.getSubcommand("extra"))

        registry.reloadAll()
        registry.disableAll()
        assertFalse(registry.isActive("claim"))
        assertEquals(
            listOf("enable:claim", "enable:addon", "reload:claim", "disable:addon", "disable:claim"),
            lifecycleEvents
        )
    }

    @Test
    fun `PluginScheduler constructs safely headless and shuts down cleanly`() {
        val scheduler = PluginScheduler(null)
        assertFalse(scheduler.isFolia)
        scheduler.shutdown()
    }

    private fun proxyPlugin(): Plugin {
        val logger = Logger.getLogger("ClaimsModuleTest")
        return Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getLogger" -> logger
                "getName" -> "ClaimsTest"
                else -> null
            }
        } as Plugin
    }
}
