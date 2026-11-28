# Claims

**Claims** is a preventive land protection, hierarchical trust matrix, and territory grief-prevention plugin for **Paper (`1.20.4–26.3+`)** and **Folia**.

> **Companion Plugin:** Forensic block/container audit logging, rollbacks, global TNT/fire/mob guards, and death-drop protection live in [**Protector**](../Protector/README.md).

## Key Features

- **Land Claiming & Subclaims**:
  - Interactive `GOLDEN_SHOVEL` corner selection (create, resize by clicking a corner, or subdivide) and `/claims claim [radius]` quick-claim.
  - Subclaims (`/claims subdivide`) support 3D Y-bounds and optional parent inheritance isolation (`/claims restrict`).
  - `/claims extend [blocks]` expands or shrinks in your facing direction; `/claims abandon` and `/claims abandonall` refund claim blocks according to `abandon-refund-ratio`.
  - Admin claims (`/claims adminclaims`) with infinite/bypassed territory protection.
- **Hierarchical Trust Matrix**:
  - Tiers (highest to lowest): `OWNER` > `MANAGE` > `BUILD` > `CONTAINER` > `ACCESS`.
  - Supports individual players (resolved safely via online/cached lookup without blocking Mojang HTTP calls) as well as `public` trust.
  - `/claims untrust all` clears all trust entries in the current claim.
- **Per-Claim Protection Flags & Territory Guards**:
  - Configurable per-claim flags: `pvp`, `explosions`, `fire_spread`, `mob_griefing`, `public_access`, `piston_cross_border`, `entry_notify`.
  - Protects blocks, containers (chests, barrels, crafters, chiseled bookshelves, decorated pots, lecterns, campfires, beehives, cauldrons, etc.), farmland/turtle & sniffer eggs, animals/villagers/vehicles/hanging entities, bucket-entity & shearing interactions.
  - Prevents cross-border piston pushes/pulls (including empty piston heads), cross-border liquid/dispenser/hopper transfers, cross-border tree growth (`limit-tree-growth`), non-player portals (`prevent-non-player-portals`), explosion entity/block damage, and nether-portal traps.
- **Claim Block Economy & Maintenance**:
  - Initial claim block allowance (`initial-claim-blocks`), AFK-aware active playtime accrual (`accrual-blocks-per-hour`, capped at `max-accrued-blocks`), bonus blocks (`/claims adjustblocks`), and optional inactive claim cleanup (`unused-cleanup`).
- **Visualization, HUD & Escape**:
  - `STICK` inspection tool (or `/claims info`) with client-side glowing/particle boundary visualization (`BoundaryVisualizer`).
  - Priority-ordered ActionBar HUD (tool preview & territory status) and optional BossBar banners (`ActionBarService`, `BossBarService`).
  - `/claims trapped` teleports trapped players asynchronously (`teleportAsync`) to nearby safe wilderness.
- **Multilingual & Hybrid UI (`1.20.4–26.3+`)**:
  - English (`en`) and Korean (`ko`) locales (`messages_en.yml`, `messages_ko.yml`) using MiniMessage.
  - Automatically uses native **Paper Dialogs (`1.21.6+`)** when supported and falls back seamlessly to **Inventory GUIs** on older versions.
- **Folia & Paper Ready**:
  - Uses `PluginScheduler` (`GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler`, `AsyncScheduler`) and `teleportAsync` across movement accrual, boundary visualization, portal rescue, and `/claims trapped`.

## Trust Tiers

| Tier | Shorthands | Capabilities |
| --- | --- | --- |
| `ACCESS` | `a`, `at` | Doors, trapdoors, gates, buttons, levers, beds, pressure plates, vehicle entry |
| `CONTAINER` | `c`, `ct` | `ACCESS` + chests/containers, anvils, beacons, lecterns, animals, shearing, bucket-entity, item frames/armor stands |
| `BUILD` | `b`, `tr`, `trust` | `CONTAINER` + place/break blocks, buckets, farmland/eggs, portals, hanging blocks |
| `MANAGE` | `m`, `mt`, `permission` | `BUILD` + manage trusts, subclaims, resize/extend, rename/greeting, flags |
| `OWNER` | — | Full control including ownership transfer and claim abandonment |

## Per-Claim Flags (`claims.default-flags`)

| Flag Key | Default | Description |
| --- | --- | --- |
| `pvp` | `false` | Allow PvP melee, projectile, and harmful splash potion damage inside the claim |
| `explosions` | `false` | Allow TNT/creeper/bed/anchor explosion block and passive-entity damage |
| `fire-spread` | `false` | Allow fire ignition/spread and block burning |
| `mob-griefing` | `false` | Allow non-player mobs (endermen, withers, ravagers, etc.) to alter blocks (`FallingBlock` sand/gravel still works) |
| `public-access` | `false` | Grant `ACCESS` permission to all players by default |
| `piston-cross-border` | `false` | Allow pistons outside the claim to push/pull across the claim boundary |
| `entry-notify` | `true` | Show the claim's custom entry greeting when entering |

## Commands & Permissions

Root command: `/claims` (alias: `/cl`). Standalone aliases are registered dynamically into Bukkit's `CommandMap` when `command-aliases.register-standalone-aliases: true` (customizable in `command-aliases.yml` and `aliases.yml`).

| Command | Aliases | Description | Permission |
| --- | --- | --- | --- |
| `/claims` | `/cl`, `menu`, `gui` | Open the Claims dashboard GUI | `claims.use` |
| `/claims help` | `?` | List commands available to you | `claims.use` |
| `/claims info` | `inspect` | Inspect the claim you are standing in | `claims.use` |
| `/claims list` | `claimslist` | Browse claims you own or are trusted in | `claims.use` |
| `/claims trapped` | — | Escape to nearby wilderness if trapped | `claims.use` |
| `/claims cancel` | — | Cancel active selection and clear visualization | `claims.use` |
| `/claims claim [radius]` | — | Claim a square area centered on your position | `claims.claim` |
| `/claims settings` | `flags`, `claimsettings` | Edit claim name, greeting, and flags | `claims.claim` |
| `/claims rename <name>` | — | Rename the current claim | `claims.claim` |
| `/claims greeting <msg\|clear>` | — | Set or clear the entry greeting message | `claims.claim` |
| `/claims trust <player\|public> [tier]` | `tr` | Grant trust (`access`, `container`, `build`, `manage`) | `claims.claim` |
| `/claims untrust <player\|public\|all>` | `ut` | Revoke trust for one target or everyone (`all`) | `claims.claim` |
| `/claims extend <blocks>` | `extendclaim`, `expand` | Expand or shrink claim in facing direction | `claims.claim` |
| `/claims transfer <player>` | `transferclaim` | Transfer claim ownership | `claims.claim` |
| `/claims restrict` | `restrictsubclaim`, `rsc` | Toggle subclaim inheritance isolation (`inheritNothing`) | `claims.claim` |
| `/claims subdivide` | `subdivideclaims`, `sc` | Toggle subclaim creation shovel mode | `claims.claim` |
| `/claims abandon` | `abandonclaim`, `unclaim` | Abandon the current claim | `claims.claim` |
| `/claims abandonall` | `abandonallclaims` | Abandon all personal claims | `claims.claim` |
| `/claims admin` | — | Open the Admin Control Panel GUI | `claims.admin` |
| `/claims adminclaims` | `ac` | Toggle admin claim shovel mode | `claims.admin` |
| `/claims adjustblocks <player> <delta>` | `adjustclaimblocks`, `acb` | Adjust bonus claim blocks | `claims.admin` |
| `/claims status` | — | Show loaded claims, modules, aliases, and runtime mode | `claims.admin` |
| `/claims reload` | — | Reload `config.yml`, locales, modules, and aliases | `claims.admin` |
| `/claims alias <add\|remove\|list>` | `aliases` | Manage custom command aliases in `aliases.yml` | `claims.admin` |

## Architecture

```text
src/main/kotlin/claims/
├── Claims.kt                         # JavaPlugin bootstrap & lifecycle
├── core/                             # Reusable plugin infrastructure (module-agnostic)
│   ├── ClaimsContext.kt              # Service composition root
│   ├── CoreHost.kt                   # Minimal host contract for core services
│   ├── command/                      # CommandRegistry, RootCommandExecutor, dynamic aliases & macros
│   ├── compat/                       # Reflective Paper Dialog & Folia capability probes
│   ├── config/                       # Validated config.yml model
│   ├── locale/                       # MiniMessage YAML localization + fallback
│   ├── module/                       # FeatureModule lifecycle registry
│   ├── scheduler/                    # Paper/Folia/Coroutine scheduler bridge
│   ├── storage/                      # HikariCP + SQLite WAL manager
│   └── ui/                           # ActionBar HUD, BossBar, MenuWindow, DialogKit/PaperDialogKit
└── modules/claim/                    # Land claim feature module
    ├── ClaimModels.kt                # Claim, BoundingBox, ClaimPermission, ClaimFlag, PlayerClaimData
    ├── ClaimRepository.kt            # SQLite persistence (cl_claims, cl_claim_trusts, cl_claim_flags, cl_player_claim_data)
    ├── ClaimManager.kt               # Chunk spatial index, accrual, cleanup, claim lifecycle
    ├── ClaimSessionStore.kt          # Per-player tool/bypass/HUD session state
    ├── ClaimToolService.kt           # Shovel & inspect-stick workflows
    ├── BoundaryVisualizer.kt         # Client-side block/particle boundary renderer
    ├── ProtectionListener.kt         # World, block, entity, container, explosion, piston & portal protection
    ├── ClaimModule.kt                # Subcommand registration & HUD providers
    └── ui/                           # ClaimMenus (Inventory GUI) & ClaimDialogs/PaperClaimDialogs
```

## Building & Testing

Requires **Java 25** (to run Gradle/Paper tooling; compiles **Java 17** compatible bytecode).

```bash
./gradlew clean test shadowJar
```

- Output JAR: `build/libs/Claims-1.0.0-SNAPSHOT.jar` (relocates runtime dependencies under `claims.libs.*`).
- Tests cover claim economy & persistence (`ClaimSystemTest`), protection vectors (`ProtectionLogicTest`), dynamic alias matching (`CommandAliasTest`), module/scheduler lifecycle (`ModuleAndSchedulerTest`), and English/Korean locale key + placeholder parity (`LocaleParityTest`).
