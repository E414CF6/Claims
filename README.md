# Claims

**Claims** is a preventive land protection, hierarchical trust matrix, and territory grief-prevention plugin for Paper
(`1.20.4–26.3+`) and Folia.

> **Companion Plugin:** Forensic block/container audit logging, rollbacks, global TNT/fire/mob guards, and death-drop
> protection live in [**Protector**](../Protector/README.md).

## Key Features

- **Land Claiming & Subclaims**:
    - Interactive `GOLDEN_SHOVEL` selection tool and `/claims claim [radius]` quick-claim, `/claims subdivide` for
      subclaims, `/claims extend`, `/claims resize`/`abandon`/`abandonall`.
    - Admin claims (`/claims adminclaims`) with infinite/bypassed territory protection.
- **Hierarchical Trust Matrix**:
    - Roles: `ACCESS` (doors/buttons/switches), `CONTAINER` (chests/inventories + access), `BUILD` (place/break +
      container + access), `MANAGER` (manage trusts & flags).
    - Supports individual players as well as `public` trust.
- **Per-Claim Protection Flags & Territory Guards**:
    - Configurable flags: `pvp`, `explosions`, `fire-spread`, `mob-griefing`, `public-access`, `piston-cross-border`,
      `entry-notify`.
    - Guards pistons crossing claim borders, fluid/fire spread across borders, tree growth across borders
      (`limit-tree-growth`), non-player portals (`prevent-non-player-portals`), explosion damage, entity interaction,
      and PVP/container/build actions.
- **Claim Block Economy & Maintenance**:
    - Initial claim block allowance (`initial-claim-blocks`), online playtime accrual (`accrual-blocks-per-hour`, capped
      at `max-accrued-blocks`), bonus blocks (`/claims adjustblocks`), and optional inactive claim cleanup
      (`unused-cleanup`).
- **Visualization & Escape**:
    - `STICK` inspection tool with glowing/particle boundary visualization (`BoundaryVisualizer`).
    - `/claims trapped` teleports trapped players safely outside the claim boundary.
- **Multilingual & Hybrid UI (`1.20.4–26.3+`)**:
    - English (`en`) and Korean (`ko`) locales (`messages_en.yml`, `messages_ko.yml`).
    - Uses Paper Dialog API (`1.21.6+`) when available and falls back to Inventory GUIs on older versions.

## Commands & Permissions

Root command: `/claims` (alias: `/cl`). Standalone subcommand aliases are registered dynamically when
`command-aliases.register-standalone-aliases: true`.

| Command | Description | Permission |
| --- | --- | --- |
| `/claims` (`/cl`) | Open the Claims dashboard GUI | `claims.use` |
| `/claims claim [radius]` | Claim area around you (or use golden shovel) | `claims.claim` |
| `/claims list` | List your claims and remaining blocks | `claims.use` |
| `/claims settings` | Edit flags of the claim you are standing in | `claims.claim` |
| `/claims trust <player\|public> [role]` | Grant trust (`access`, `container`, `build`, `manager`) | `claims.claim` |
| `/claims untrust <player\|public\|all>` | Revoke trust | `claims.claim` |
| `/claims extend [blocks]` | Expand the current claim outward | `claims.claim` |
| `/claims transfer <player>` | Transfer claim ownership | `claims.claim` |
| `/claims restrict` | Toggle subclaim trust inheritance restriction | `claims.claim` |
| `/claims subdivide` | Enter subclaim creation mode | `claims.claim` |
| `/claims abandon` / `abandonall` | Abandon current claim or all personal claims | `claims.claim` |
| `/claims trapped` | Escape from a claim you cannot build in | `claims.use` |
| `/claims cancel` | Cancel active selection or subdivide mode | `claims.use` |
| `/claims adminclaims` / `adjustblocks` | Admin claim mode & bonus claim block adjustments | `claims.admin` |
| `/claims status` / `reload` / `alias` | Plugin status, config reload, and alias manager | `claims.admin` |

## Building

Requires **Java 25** (to run Gradle/Paper tooling; compiles Java 17 compatible bytecode).

```bash
./gradlew clean test shadowJar
```

Output JAR: `build/libs/Claims-1.0.0-SNAPSHOT.jar` (all runtime dependencies relocated under `claims.libs.*`).
