package claims

import claims.core.compat.PlatformCompat
import claims.core.locale.MessageService
import claims.core.scheduler.PluginScheduler
import claims.core.storage.DatabaseManager
import claims.core.ui.ActionBarService
import claims.core.ui.BossBarService
import claims.core.ui.MenuWindow
import claims.modules.claim.*
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Dispenser
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class ProtectionLogicTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plugin yml declares main class claims Claims, Folia support, and single root command`() {
        val stream = Claims::class.java.classLoader.getResourceAsStream("plugin.yml")
        requireNotNull(stream) { "plugin.yml must exist on classpath" }
        val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(stream))
        assertEquals("claims.Claims", yaml.getString("main"))
        assertEquals("1.20", yaml.getString("api-version"))
        assertTrue(yaml.getBoolean("folia-supported"), "folia-supported must be true")
        assertTrue(yaml.contains("commands.claims"), "Expected root command claims in plugin.yml")
        val registeredCommands = yaml.getConfigurationSection("commands")?.getKeys(false).orEmpty()
        assertEquals(
            setOf("claims"),
            registeredCommands,
            "Standalone aliases should be managed dynamically via CommandAliasService"
        )
    }

    @Test
    fun `compiled classes target Java 17 bytecode and core package has zero module imports`() {
        val classStream = Claims::class.java.classLoader.getResourceAsStream("claims/Claims.class")
        requireNotNull(classStream)
        val bytes = classStream.readNBytes(8)
        val majorVersion = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(61, majorVersion, "Claims should compile to Java 17 bytecode (major version 61)")
        assertTrue(PlatformCompat.hasClass("claims.core.ui.DialogKit"))
        assertFalse(PlatformCompat.hasClass("non.existent.ClassName"))

        // Reusability check: core (except ClaimsContext/ClaimsConfig) must not depend on claims.modules
        Files.walk(Path.of("src/main/kotlin/claims/core")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { path ->
                if (path.fileName.toString() in setOf("ClaimsConfig.kt", "ClaimsContext.kt")) return@forEach
                val src = Files.readString(path)
                assertFalse(
                    src.contains("claims.modules."),
                    "Reusable core file ${path.fileName} must not import domain module packages"
                )
            }
        }
    }

    @Test
    fun `MenuWindow holds inventory rows cleanly`() {
        assertEquals(28, MenuWindow.CONTENT_SLOTS.size)
    }

    @Test
    fun `protection listener cancels unauthorized break, cross-border liquid, and cross-border dispenser`() {
        val db = DatabaseManager(File(tempDir.toFile(), "prot_logic.db"), 2, "Claims-Test")
        try {
            val cfg = ClaimSystemTest.defaultTestConfig()
            val repo = ClaimRepository(db)
            val claimManager = ClaimManager(null, repo) { cfg }.apply { loadAll() }

            val world = proxyWorld("world")
            val ownerId = UUID.randomUUID()
            val intruderId = UUID.randomUUID()

            val res = claimManager.createClaim(ownerId, BoundingBox.ofCorners("world", 0, -64, 0, 10, 319, 10))
            val claim = assertInstanceOf(ClaimResult.Success::class.java, res).claim

            val actionBarMessages = ArrayList<Component>()
            val ownerPlayer = proxyPlayer(ownerId, "Owner", world, actionBarMessages)
            val intruderPlayer = proxyPlayer(intruderId, "Intruder", world, actionBarMessages)

            val fakePlugin = sun.misc.Unsafe::class.java
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
                .let { (it as sun.misc.Unsafe).allocateInstance(Claims::class.java) as Claims }

            val scheduler = PluginScheduler(null)
            val messages = MessageService(fakePlugin)
            val bossBar = BossBarService(scheduler) { cfg.bossBarEnabled }
            val actionBar = ActionBarService(scheduler, { cfg.actionBarSettings() }, messages)
            val visualizer = BoundaryVisualizer(scheduler) { cfg }
            val sessions = ClaimSessionStore()
            val toolService =
                ClaimToolService({ cfg }, messages, claimManager, visualizer, actionBar, bossBar, sessions)
            val listener = ProtectionListener(
                scheduler,
                { cfg },
                messages,
                claimManager,
                toolService,
                visualizer,
                actionBar,
                bossBar,
                sessions,
            )

            val insideBlock = proxyBlock(world, 5, 64, 5)
            val outsideBlock = proxyBlock(world, 20, 64, 20)

            val deniedBreak = BlockBreakEvent(insideBlock, intruderPlayer)
            listener.onBlockBreak(deniedBreak)
            assertTrue(deniedBreak.isCancelled)
            assertTrue(actionBarMessages.isNotEmpty(), "ActionBar should receive denial feedback")

            val ownerBreak = BlockBreakEvent(insideBlock, ownerPlayer)
            listener.onBlockBreak(ownerBreak)
            assertFalse(ownerBreak.isCancelled)

            claimManager.setTrust(claim, intruderId.toString(), ClaimPermission.BUILD)
            val trustedBreak = BlockBreakEvent(insideBlock, intruderPlayer)
            listener.onBlockBreak(trustedBreak)
            assertFalse(trustedBreak.isCancelled)

            val flowEvent = BlockFromToEvent(outsideBlock, insideBlock)
            listener.onLiquidFlow(flowEvent)
            assertTrue(flowEvent.isCancelled)

            val claimBorderBlock = proxyBlock(world, 0, 64, 5)
            val dispenserBlock = proxyDispenserBlock(world, -1, 64, 5, BlockFace.EAST, claimBorderBlock)
            val dummyStack = sun.misc.Unsafe::class.java
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
                .let { (it as sun.misc.Unsafe).allocateInstance(ItemStack::class.java) as ItemStack }
            val dispenseEvent = BlockDispenseEvent(dispenserBlock, dummyStack, org.bukkit.util.Vector(1, 0, 0))
            listener.onDispense(dispenseEvent)
            assertTrue(dispenseEvent.isCancelled)
        } finally {
            db.close()
        }
    }

    private fun proxyWorld(name: String): World =
        Proxy.newProxyInstance(
            World::class.java.classLoader,
            arrayOf(World::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> name
                "hashCode" -> name.hashCode()
                "equals" -> false
                else -> null
            }
        } as World

    private fun proxyBlock(world: World, x: Int, y: Int, z: Int): Block {
        val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        return Proxy.newProxyInstance(
            Block::class.java.classLoader,
            arrayOf(Block::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getWorld" -> world
                "getLocation" -> loc
                "getX" -> x
                "getY" -> y
                "getZ" -> z
                else -> null
            }
        } as Block
    }

    private fun proxyDispenserBlock(world: World, x: Int, y: Int, z: Int, facing: BlockFace, target: Block): Block {
        val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        val dispenserData = Proxy.newProxyInstance(
            Dispenser::class.java.classLoader,
            arrayOf(Dispenser::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getFacing" -> facing
                else -> null
            }
        } as Dispenser
        return Proxy.newProxyInstance(
            Block::class.java.classLoader,
            arrayOf(Block::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getWorld" -> world
                "getLocation" -> loc
                "getX" -> x
                "getY" -> y
                "getZ" -> z
                "getBlockData" -> dispenserData
                "getRelative" -> target
                else -> null
            }
        } as Block
    }

    private fun proxyPlayer(uuid: UUID, name: String, world: World, actionBars: MutableList<Component>): Player {
        val loc = Location(world, 0.0, 64.0, 0.0)
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getUniqueId" -> uuid
                "getName" -> name
                "getWorld" -> world
                "getLocation" -> loc
                "hasPermission" -> false
                "sendActionBar" -> {
                    (args?.firstOrNull() as? Component)?.let { actionBars.add(it) }
                    null
                }

                "hashCode" -> uuid.hashCode()
                "equals" -> false
                else -> null
            }
        } as Player
    }
}
