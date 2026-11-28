package claims

import claims.core.config.ClaimsConfig
import claims.core.storage.DatabaseManager
import claims.modules.claim.*
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.*

class ClaimSystemTest {
    private lateinit var db: DatabaseManager
    private lateinit var repo: ClaimRepository
    private lateinit var claimManager: ClaimManager

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        db = DatabaseManager(File(tempDir.toFile(), "test_claims.db"), poolSize = 2, poolName = "Claims-Test")
        repo = ClaimRepository(db)
        val cfg = defaultTestConfig()
        claimManager = ClaimManager(null, repo) { cfg }
        claimManager.loadAll()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `permission hierarchy follows Owner-Manage-Build-Container-Access`() {
        assertTrue(ClaimPermission.ACCESS.isGrantedBy(ClaimPermission.BUILD))
        assertTrue(ClaimPermission.CONTAINER.isGrantedBy(ClaimPermission.BUILD))
        assertTrue(ClaimPermission.BUILD.isGrantedBy(ClaimPermission.MANAGE))
        assertFalse(ClaimPermission.BUILD.isGrantedBy(ClaimPermission.CONTAINER))
        assertFalse(ClaimPermission.MANAGE.isGrantedBy(ClaimPermission.BUILD))
    }

    @Test
    fun `claim creation, overlap rejection, resize, reload persistence, and deletion refund`() {
        val owner = UUID.randomUUID()
        val stranger = UUID.randomUUID()

        val initialAvailable = claimManager.getAvailableClaimBlocks(owner)
        assertEquals(400, initialAvailable)

        val bounds = BoundingBox.ofCorners("world", 0, -64, 0, 9, 319, 9)
        val created = claimManager.createClaim(owner, bounds)
        val claim = assertInstanceOf(ClaimResult.Success::class.java, created).claim

        assertEquals(100, claim.bounds.area)
        assertEquals(300, claimManager.getAvailableClaimBlocks(owner))
        assertTrue(claim.hasPermission(owner, ClaimPermission.OWNER))
        assertFalse(claim.hasPermission(stranger, ClaimPermission.ACCESS))

        val overlap = claimManager.createClaim(stranger, BoundingBox.ofCorners("world", 5, -64, 5, 14, 319, 14))
        val fail = assertInstanceOf(ClaimResult.Failure::class.java, overlap)
        assertEquals(ClaimResult.Reason.OVERLAPS_EXISTING, fail.reason)

        val resized = claimManager.resizeClaim(claim, BoundingBox.ofCorners("world", 0, -64, 0, 14, 319, 9))
        assertInstanceOf(ClaimResult.Success::class.java, resized)
        assertEquals(250, claimManager.getAvailableClaimBlocks(owner))

        claimManager.setTrust(claim, stranger.toString(), ClaimPermission.CONTAINER)
        claimManager.setFlag(claim, ClaimFlag.PVP, true)
        claimManager.updateMetadata(claim, "My Base", "Welcome!")

        val reloaded = ClaimManager(null, repo) { defaultTestConfig() }.apply { loadAll() }
        val persisted = reloaded.getTopLevelClaimAt("world", 5, 5)
        assertNotNull(persisted)
        assertEquals("My Base", persisted!!.name)
        assertEquals("Welcome!", persisted.greeting)
        assertTrue(persisted.flag(ClaimFlag.PVP))
        assertTrue(persisted.hasPermission(stranger, ClaimPermission.CONTAINER))
        assertFalse(persisted.hasPermission(stranger, ClaimPermission.BUILD))

        val refund = reloaded.deleteClaim(persisted)
        assertEquals(150, refund)
        assertEquals(400, reloaded.getAvailableClaimBlocks(owner))
        assertNull(reloaded.getTopLevelClaimAt("world", 5, 5))
    }

    @Test
    fun `subclaim inherits parent trust unless inheritNothing is enabled`() {
        val owner = UUID.randomUUID()
        val friend = UUID.randomUUID()
        val parent = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createClaim(owner, BoundingBox.ofCorners("world", 100, -64, 100, 119, 319, 119))
        ).claim
        claimManager.setTrust(parent, friend.toString(), ClaimPermission.BUILD)

        val sub1 = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createSubclaim(
                parent,
                BoundingBox.ofCorners("world", 102, 60, 102, 108, 70, 108),
                inheritNothing = false
            )
        ).claim
        assertTrue(sub1.hasPermission(friend, ClaimPermission.BUILD))

        val sub2 = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createSubclaim(
                parent,
                BoundingBox.ofCorners("world", 110, 60, 110, 116, 70, 116),
                inheritNothing = true
            )
        ).claim
        assertFalse(sub2.hasPermission(friend, ClaimPermission.ACCESS))

        claimManager.setTrust(sub2, friend.toString(), ClaimPermission.CONTAINER)
        assertTrue(sub2.hasPermission(friend, ClaimPermission.CONTAINER))
        assertNotNull(claimManager.getClaimById(sub2.id))
    }

    @Test
    fun `extendClaim, transferClaim, deleteAllPlayerClaims, and cleanupUnusedClaims work`() {
        val owner = UUID.randomUUID()
        val newOwner = UUID.randomUUID()
        val claim = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createClaim(owner, BoundingBox.ofCorners("world", 0, -64, 0, 9, 319, 9))
        ).claim

        val extended = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.extendClaimInDirection(claim, org.bukkit.block.BlockFace.EAST, 5)
        ).claim
        assertEquals(14, extended.bounds.maxX)
        assertEquals(150, extended.bounds.area)

        claimManager.transferClaim(extended, newOwner)
        assertEquals(newOwner, extended.ownerId)
        assertEquals(1, claimManager.getPlayerClaims(newOwner).size)
        assertEquals(0, claimManager.getPlayerClaims(owner).size)

        val (count, refund) = claimManager.deleteAllPlayerClaims(newOwner)
        assertEquals(1, count)
        assertEquals(150, refund)

        val staleOwner = UUID.randomUUID()
        val data = claimManager.getOrCreatePlayerData(staleOwner)
        data.lastActiveAt = System.currentTimeMillis() - (90L * 86_400_000L)
        repo.savePlayerData(data)

        claimManager.createClaim(staleOwner, BoundingBox.ofCorners("world", 50, -64, 50, 59, 319, 59))
        val cleaned = claimManager.cleanupUnusedClaims()
        assertEquals(1, cleaned)
        assertNull(claimManager.getTopLevelClaimAt("world", 55, 55))
    }

    @Test
    fun `abandonRefundRatio below 1 deducts unrefunded penalty from owner balance`() {
        val halfRefundCfg = defaultTestConfig().copy(abandonRefundRatio = 0.5)
        val halfManager = ClaimManager(null, repo) { halfRefundCfg }.apply { loadAll() }
        val owner = UUID.randomUUID()

        val claim = assertInstanceOf(
            ClaimResult.Success::class.java,
            halfManager.createClaim(owner, BoundingBox.ofCorners("world", 0, -64, 0, 9, 319, 9))
        ).claim
        assertEquals(100, claim.bounds.area)
        assertEquals(300, halfManager.getAvailableClaimBlocks(owner))

        val refund = halfManager.deleteClaim(claim)
        assertEquals(50, refund)
        assertEquals(350, halfManager.getAvailableClaimBlocks(owner))
    }

    @Test
    fun `extendClaim rejects inversion, resize validates children and world, and removeAllTrusts clears all`() {
        val owner = UUID.randomUUID()
        val friend1 = UUID.randomUUID()
        val friend2 = UUID.randomUUID()

        val claim = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createClaim(owner, BoundingBox.ofCorners("world", 0, -64, 0, 9, 319, 9))
        ).claim

        val inverted = claimManager.extendClaimInDirection(claim, org.bukkit.block.BlockFace.WEST, -20)
        val invertFail = assertInstanceOf(ClaimResult.Failure::class.java, inverted)
        assertEquals(ClaimResult.Reason.TOO_SMALL, invertFail.reason)
        assertEquals(0, claim.bounds.minX)

        val wrongWorld = claimManager.resizeClaim(claim, BoundingBox.ofCorners("world_nether", 0, 0, 0, 9, 128, 9))
        assertEquals(
            ClaimResult.Reason.WORLD_DISABLED,
            assertInstanceOf(ClaimResult.Failure::class.java, wrongWorld).reason
        )

        assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createSubclaim(claim, BoundingBox.ofCorners("world", 2, 60, 2, 7, 70, 7))
        )
        val shrinkPastChild = claimManager.resizeClaim(claim, BoundingBox.ofCorners("world", 0, -64, 0, 5, 319, 5))
        assertEquals(
            ClaimResult.Reason.OUTSIDE_PARENT,
            assertInstanceOf(ClaimResult.Failure::class.java, shrinkPastChild).reason
        )

        claimManager.setTrust(claim, friend1.toString(), ClaimPermission.BUILD)
        claimManager.setTrust(claim, friend2.toString(), ClaimPermission.ACCESS)
        claimManager.setTrust(claim, Claim.PUBLIC_KEY, ClaimPermission.ACCESS)
        claimManager.removeAllTrusts(claim)
        assertTrue(claim.trusts.isEmpty())

        val reloaded = ClaimManager(null, repo) { defaultTestConfig() }.apply { loadAll() }
        val persisted = reloaded.getClaimById(claim.id)!!
        assertTrue(persisted.trusts.isEmpty())

        claimManager.setTrust(persisted, friend1.toString(), ClaimPermission.MANAGE)
        claimManager.transferClaim(persisted, friend1)
        assertFalse(persisted.trusts.containsKey(friend1.toString()))
    }

    companion object {
        fun defaultTestConfig(): ClaimsConfig = ClaimsConfig(
            language = "en",
            databaseFile = "test_claims.db",
            databasePoolSize = 2,
            enabledWorlds = emptySet(),
            claimTool = Material.GOLDEN_SHOVEL,
            inspectTool = Material.STICK,
            initialClaimBlocks = 400,
            accrualBlocksPerHour = 120,
            maxAccruedBlocks = 80000,
            minClaimWidth = 5,
            minClaimArea = 25,
            autoClaimRadius = 7,
            abandonRefundRatio = 1.0,
            visualizationDurationSeconds = 30,
            defaultFlags = mapOf(
                ClaimFlag.PVP to false,
                ClaimFlag.EXPLOSIONS to false,
                ClaimFlag.FIRE_SPREAD to false,
                ClaimFlag.MOB_GRIEFING to false,
                ClaimFlag.PUBLIC_ACCESS to false,
                ClaimFlag.PISTON_CROSS_BORDER to false,
                ClaimFlag.ENTRY_NOTIFY to true
            ),
        )
    }
}
