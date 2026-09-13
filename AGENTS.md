# Puxadinho

A server-side Project Zomboid Build 42 Java agent containing small, isolated
patches. It is packaged as a **plain Java agent** — no mod framework, no
ZombieBuddy, no `mod.info`.

Current patches:

1. **Ranch animals die off in old worlds** — once the world passes ~60 days,
   ranch-spawned animals are increasingly killed on spawn; past ~190/250 days
   the chance becomes 100%, so no live animals appear.
2. **Player stat tracking** — persists a time-series of every survivor's
   progress (kills, hours survived, logon times, position, health, infection,
   inventory and skills) to a SQLite database that survives character death.
3. **Death notifications** — announces every player death to server chat with a
   message chosen by the cause of death, fully configurable per cause.
4. **Ranch respawn** — refills a ranch's animals once its herd has been wiped
   out for `RanchRespawnHours` (default 168) in-game hours.
5. **Vehicle respawn** — a ticket-based economy: removed/abandoned vehicles earn
   tickets, which the server spends to spawn zone-appropriate vehicles near
   random online players.
6. **Safehouse item protection** — stops the sandbox dropped-item removal timer
   from deleting world items whose square is inside a safehouse.
7. **Server performance tracking** — samples JVM health (heap, GC, CPU, threads)
   alongside game state (online players, loaded zombies/animals/vehicles, world
   age) into a SQLite database so resource use can be correlated with server
   activity.

## Architecture

The agent uses `java.lang.instrument` (`premain`) and a single
`ClassFileTransformer`. Every fix is a self-contained `Patch`:

```java
public interface Patch {
    boolean matches(String className);
    byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception;
}
```

`PatchTransformer` holds the list of patches and applies **every** patch that
claims a loaded class, in list order, feeding each one the bytes produced by the
previous patch. This lets independent patches hook the same method (e.g. the
stats and death-notification patches both hook `IsoPlayer.onKilled`) without
knowing about each other. Adding a fix means adding one file and one line in
`PatchTransformer`; patches never touch each other's code. The only bundled
library is ASM (`org.ow2.asm:asm`), shaded into the agent jar.

### Patch 1 — ranch animal age die-off (`patches/ranch`)

In `zombie.randomizedWorld.randomizedRanch.RandomizedRanchBase.randomizeRanch`
there is a world-age gate:

```java
if (GameTime.getInstance().getWorldAgeDaysSinceBegin() > 60.0) {
    int randValue = Math.max(0, 190 - (int)GameTime.getInstance().getWorldAgeDaysSinceBegin());
    if (Rand.NextBool(randValue)) {
        animal.setHealth(0.0F);
        ...
    }
}
```

`Rand.NextBool(0)` always returns `true`, so once the world is old enough the
spawned animals are guaranteed to be created dead. The patch rewrites the
`60.0` constant in this method to `Double.MAX_VALUE`, so the age gate is never
entered and ranch animals always spawn alive. It only touches the two `60.0`
loads inside `randomizeRanch`.

### Patch 2 — player stat tracking (`patches/stats`)

The vanilla server keeps one live row per character in `players.db` and deletes
it when the character dies, so there is no history. This patch samples every
online survivor and appends immutable snapshots to a separate SQLite database,
`puxadinho_stats.db`, created next to `players.db` in the current save.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.savefile.ServerPlayerDB` | `serverUpdateNetworkCharacter` | queues a snapshot each time the server saves a player (every ~3 min per player, plus world saves, login/character creation and disconnect) |
| `zombie.characters.IsoPlayer` | `onKilled` | queues a final snapshot plus a `player_deaths` row (killer, weapon, illness cause, PvP flag) |
| `zombie.core.raknet.UdpConnection` | `setFullyConnected` | records the login timestamp used for `last_logon` |

`StatsGuard` reads the game state on the server thread (the only safe place to
touch `IsoPlayer`), then hands plain data to a daemon writer thread that batches
inserts. Captures are stack-neutral static calls inserted at method entry, so
the patch only needs `COMPUTE_MAXS`. Schema:

- `player_stats` — one row per `(world, steamid, username, name)` with
  `first_seen`, `last_seen`, `last_logon` and `profession`.
- `stat_snapshots` — time series keyed to `player_stats.id`: timestamp,
  `x/y/z`, `hours_survived`, `zombie_kills`, `survivor_kills`,
  `last_zombie_kills`, `health`, `infected`, `dead`, inventory
  weight/capacity, an aggregated `inventory` string, per-perk `skills`, plus
  the illness stats `zombie_infection`, `food_sickness`, `poison`,
  `wound_infection`, `sickness`, `hunger`, `thirst`.
- `player_deaths` — one row per death keyed to `player_stats.id`: timestamp,
  `x/y/z`, `killer_name`, `killer_username`, `killer_type`
  (`player`/`zombie`/`animal`/`fire`/`fall`/`infection`/`wound`/`food`/
  `poison`/`thirst`/`hunger`/`sickness`/`environment`), `weapon`, `cause` (the
  machine cause key, same vocabulary as `killer_type`; the human-readable chat
  wording lives in patch 3), a `pvp` flag and the same seven illness columns.

For illness deaths (no attacker), `killer_type` is inferred from
`BodyDamage.isInfected()` / `ZOMBIE_INFECTION` (zombie virus), then wound
infection, food sickness, poison, thirst, hunger and general sickness. Because
the same stats are stored on every snapshot, infection progression is visible
over time, not just at the moment of death.

Because history is append-only, the data survives death and respawn and can
answer "what was this player doing yesterday". If the database cannot be
opened the patch logs once and drops snapshots rather than affecting the game.

### Patch 3 — death notifications (`patches/death`)

Announces every player death to server chat with a message chosen by the cause
of death. This used to be baked into the stats patch; it is now separate so the
wording can be configured (or disabled) without touching `puxadinho_stats.db`.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.characters.IsoPlayer` | `onKilled` | classifies the cause of death and sends the matching configured message to server chat |

Cause classification is identical to the stats patch: the killer object
(`IsoPlayer` / `IsoZombie` / animal), then `isOnFire` / `isKilledByFall`, then
the illness vitals (zombie infection, wound infection, food sickness, poison,
thirst, hunger, sickness), falling back to `environment`. The resulting key
(`player`, `zombie`, `animal`, `fire`, `fall`, `infection`, `wound`, `food`,
`poison`, `thirst`, `hunger`, `sickness`, `environment`) selects the message
template `DeathMessage<Cause>` from `Puxadinho.ini`.

Each template may use these placeholders:

| Placeholder | Expands to |
|-------------|-----------|
| `{player}` | character name, plus username in parentheses when known: `John Doe (steamuser)` |
| `{name}` | character name only |
| `{username}` | account username only (empty if unknown) |
| `{killer}` | `a zombie`, `an animal`, a player's character name, or empty |
| `{weapon}` | weapon name (empty if none) |
| `{weapon_suffix}` | `" (Weapon)"` when a weapon was used, otherwise empty |
| `{survived}` | survival time, e.g. `7 days 18 hours` or `1 month 2 days 3 hours` (a month is 30 days; under an hour reads `less than an hour`) |
| `{cause}` | the machine cause key |

The message is sent with `ChatServer.sendMessageToServerChat` only on the
server. Gated by `DeathMessagesEnabled` in `Puxadinho.ini`.

### Patch 4 — ranch respawn (`patches/ranch`)

Dedicated servers never refill a wiped ranch, so it stays empty forever. This
patch watches animal zones and re-runs the vanilla ranch randomization once a
herd has been missing for `RanchRespawnHours` (default 168 in-game hours). State
lives in `puxadinho_ranch.db` beside the save.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.iso.areas.DesignationZone` | `update` | drives a throttled (2.5 s) scan of `DesignationZoneAnimal` zones |

The scan looks at every `AnimalZone` zone with zero connected animals, resolves
the matching map `Ranch` zone, and arms a countdown from the first moment it was
seen empty. Once `RanchRespawnHours` have passed and the zone is fully streamed,
it calls `RandomizedRanchBase.randomizeRanch(zone, dzone)` on the existing
zone/dzone (never `checkRanchStory`, which would duplicate the zone) and
`zone.check()`. An empty ranch that fails to repopulate restarts its countdown
rather than retrying every tick. Gated by `RanchRespawnEnabled`. Database:
`ranch(x, y, z, zero_hour)`.

### Patch 5 — vehicle respawn (`patches/vehicles`)

Abandoned/removed vehicles stay gone or accumulate on dedicated servers. This
patch runs a ticket-based vehicle economy, modelled on the VLCS HDRcade mod:
removed vehicles earn tickets, and the server spends tickets to spawn
zone-appropriate vehicles near random online players. State lives in
`puxadinho_vehicles.db` beside the save. The spawn helpers (`VehicleSpawnSite`,
`VehicleZoneCache`) live in the same package.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.vehicles.VehicleManager` | `serverUpdate` | throttled (1 s) vehicle scan: stamps `last_seen` for vehicles within `VehicleRespawnChunks` of a player, hands vehicles unseen for `VehicleRespawnDays` to the janitor, and periodically spends tickets to spawn new vehicles near a random online player |
| `zombie.vehicles.BaseVehicle` | `permanentlyRemove` | grants one ticket for every vehicle removal (burnt-wreck removal, admin `/remove`, a future dismantle mod, or the janitor) |

A ticket is the currency of the system: removals earn tickets, and the
scheduler spends them. Every `VehicleRespawnDays` the janitor removes vehicles
with no nearby player (this grants a ticket). Every `VehicleSpawnFreqDays`
(default 1; `0` = hourly) the server takes up to `VehicleSpawnBatchSize`
tickets and, for each, picks the next online player round-robin and scans the
loaded chunks around them for a valid tile.

The scan is a server-side, zone-based replacement for the mod's client scan.
The map's predefined vehicle zones are declared in `media/maps/<map>/objects.lua`
as `{ name = "...", type = "ParkingStall" | "Vehicle", x, y, z, width, height }`
(the `name`, not the type, selects the distribution). The engine parses them all
into `IsoMetaGrid.vehiclesZones`; the agent deduplicates that list and writes it
to `puxadinho_vehicle_zones.txt` beside the save (`VehicleZoneCache`). Spawning
then gathers the cached zones within `VehicleSpawnMaxDistance` of the chosen
player, picks a random loaded one and a random tile inside it, and validates a
3x5 or 5x2 footprint (loaded, free, no tree/room, not intersecting a vehicle,
not a safehouse). No random tile probing across the map. The vehicle type is
chosen with the vanilla zone distribution (`VehicleType.getRandomVehicleType`),
which also applies the vanilla burnt-car chance. The facing comes from the
zone's `Direction` property (cached with each zone; falls back to the zone's
long axis), and placement mirrors vanilla `IsoChunk.AddVehicles_OnZone`: the
cross axis is centred on a tile inside the zone and the length axis is anchored
half a vehicle length from the zone edge (N/S from top/bottom, W/E from
left/right). The spawn sets the vanilla rotation quaternion and rejects
positions that collide with a nearby vehicle (`testCollisionWithVehicle`), so
vehicles no longer stack or hang outside the stall. Only tiles in chunks the
server currently has loaded are accepted, so spawns always land in loaded
territory.
Spawn reuses the vanilla path: `new BaseVehicle(cell)`, `setScriptName`,
`setScript`, `setZone`, `setVehicleType`, `setDir`, position, `setSquare`,
`chunk.vehicles.add`, `addToWorld`, `VehiclesDB2.addVehicle`, key and `repair`.
Because `repair` leaves every part pristine, the part condition is then set from
the sandbox `CarGeneralCondition` option (1 very low = 0-25, 2 low = 20-50,
3 normal = 60-100, 4 high = 75-100, 5 very high = 90-100), so respawned
vehicles match the server's setting instead of always spawning at full
condition. This gives correct multiplayer sync through the normal
`VehicleFullUpdate` stream. State is stored in
`vehicles(sql_id, script, x, y, z, last_seen)` and `state(key, value)` (tickets,
last spawn hour, round-robin index).

Known limits: the game clock pauses on an empty server (`PauseEmpty`), so
timers only advance while someone is online. B42 has no vanilla way to remove
an intact normal vehicle, so that source of tickets requires the planned
client-side dismantle mod (which will call `permanentlyRemove` and therefore
work through the same hook).

### Patch 6 — safehouse item protection (`patches/safehouse`)

The sandbox "hours for world item removal" timer culls dropped
`IsoWorldInventoryObject`s when a square is (re)loaded, inside
`IsoGridSquare.load`:

```java
... && !worldItem.isIgnoreRemoveSandbox()
    && GameTime.instance.getWorldAgeHours() > worldItem.dropTime + hrs ...
    continue;   // drops the item
```

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.iso.objects.IsoWorldInventoryObject` | `isIgnoreRemoveSandbox` | returns `true` when the object's square is inside a safehouse (live query, keeps the item on the server) |
| `zombie.iso.objects.IsoWorldInventoryObject` | `save` | sets `ignoreRemoveSandbox = true` for safehouse items before serialization, so the bit is written to the chunk file and streamed to clients (which also run the cull) |

The live `isIgnoreRemoveSandbox` override protects items immediately on the
server. The `save` hook persists the same bit into the chunk (`save` writes it,
`load` reads it back), which is what makes the client's own copy of the cull
respect it. Because the bit is stored per item, an item that was protected
while a safehouse existed **stays protected after the safehouse is deleted** —
this is intentional. `ItemSpawner` sets the same vanilla bit for loot-respawn
items, which is why the protection is permanent rather than a separable state.
Gated by `SafehouseItemProtection` in `Puxadinho.ini`.

### Patch 7 — server performance tracking (`patches/perf`)

There is no server-side way to see how JVM resource use lines up with what the
game was doing. This patch samples both every `PerfSampleSeconds` (default 60,
wall clock) and appends them to `puxadinho_perf.db` beside the save.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.network.ServerMap` | `preupdate` | throttled (1 sample per `PerfSampleSeconds`) capture of JVM and game state |

`ServerMap.preupdate` is the server's per-tick world update, so the sample is
taken on the server thread (the only safe place to read `IsoWorld`/`GameTime`).
The guard throttles itself and hands a plain record to a daemon writer thread
that batches inserts, mirroring the stats patch. JVM values come from
`java.lang.management` and `com.sun.management.OperatingSystemMXBean`; GC count
and time are stored both cumulatively and as deltas since the previous sample.
The game values are the online player count (`GameServer.getPlayers()` filtered
to non-animal survivors; unlike `getPlayerCount()` this does not skip roles with
`HideFromSteamUserList`, such as admin),
loaded zombies/animals/vehicles from the current cell and the world age in
hours. Gated by `PerfStatsEnabled`. Schema (`perf_samples`): `ts`, `epoch_ms`,
`uptime_ms`, `heap_used`, `heap_committed`, `heap_max`, `nonheap_used`,
`gc_count`, `gc_time_ms`, `gc_count_delta`, `gc_time_delta_ms`,
`process_cpu_load`, `system_cpu_load`, `thread_count`, `peak_thread_count`,
`loaded_classes`, `players`, `zombies`, `animals`, `vehicles`, `world_age_hours`.

## Configuration

On first use the agent writes `Puxadinho.ini` to the Zomboid cache
directory (`%USERPROFILE%\Zomboid\Puxadinho.ini` on Windows, next to the
`Saves` folder) and reads it back on every fresh server start. Delete it to
regenerate the defaults.

```ini
RanchRespawnEnabled = true
RanchRespawnHours = 168

VehicleRespawnEnabled = true
VehicleRespawnDays = 224
VehicleRespawnChunks = 2
VehicleTicketSystem = true
VehicleMaxTickets = 30
VehicleSpawnFreqDays = 1
VehicleSpawnBatchSize = 5
VehicleSpawnMinDistance = 55
VehicleSpawnMaxDistance = 250

StatsEnabled = true

PerfStatsEnabled = true
PerfSampleSeconds = 60

DeathMessagesEnabled = true
DeathMessagePlayer = {player} foi morto por {killer} | {weapon_suffix} | {survived}
DeathMessageZombie = {player} foi morto por {killer} | {survived}
DeathMessageAnimal = {player} foi morto por {killer} | {survived}
DeathMessageFire = {player} morreu queimado | {survived}
DeathMessageFall = {player} foi morto pela gravidade | {survived}
DeathMessageInfection = {player} virou zumbi | {survived}
DeathMessageWound = {player} morreu de infeccao | {survived}
DeathMessageFood = {player} morreu de intoxicacao alimentar | {survived}
DeathMessagePoison = {player} morreu envenenado | {survived}
DeathMessageThirst = {player} morreu de sede | {survived}
DeathMessageHunger = {player} morreu de fome | {survived}
DeathMessageSickness = {player} morreu de doenca | {survived}
DeathMessageEnvironment = {player} morreu | {survived}

SafehouseItemProtection = true

DebugLogging = false
```

`Config.load()` is lazy and safe to call from any patch; it no-ops until
`ZomboidFileSystem` is ready. Respawn times are in-world hours/days, so the
same `PauseEmpty` caveat applies. `VehicleRespawnChunks` is the radius (1
chunk = 8 tiles) that counts as a player being near a vehicle and therefore
keeps it from being aged out. `VehicleTicketSystem` gates whether spawns
require a ticket; `VehicleMaxTickets` caps the ledger; `VehicleSpawnFreqDays`
is how often tickets are spent (`0` = hourly); `VehicleSpawnBatchSize` is the
number of spawn attempts per tick; `VehicleSpawnMinDistance` and
`VehicleSpawnMaxDistance` bound the tiles search from the chosen player.
`StatsEnabled` gates patch 2. `PerfStatsEnabled` gates patch 7 and
`PerfSampleSeconds` is the wall-clock seconds between samples.
`DeathMessagesEnabled` gates patch 3; the
`DeathMessage<Cause>` values are chat templates using the placeholders listed
above. The ini is written and read as UTF-8, so non-ASCII message text is safe.
The death-message defaults are Portuguese, but every cause can be reworded
(for example into English) per server.

Ranch and vehicle respawn keep separate databases now: `puxadinho_ranch.db` and
`puxadinho_vehicles.db`. An upgrade from a build that used the combined
`puxadinho_world.db` simply starts those two fresh (the old file is ignored).

`DebugLogging` is the master switch for all informational logging from every
patch. When false the agent is silent except for genuine errors (which always
go to `System.err`). When true it also writes `Puxadinho-debug.log` next to
the loaded agent jar and, once the game is ready, in the Zomboid cache
directory; the first lines are a build/jar fingerprint (path, size, mtime,
SHA-256) that confirms the correct `Puxadinho.jar` was loaded. All logging
goes through `com.puxadinho.Debug`; patches must not call `System.out`
directly.

## Requirements

- **Java 25** (the game server ships Zulu 25; `projectzomboid.jar` classes are
  class-file version 69).
- **Gradle 9.x** — the project ships a wrapper pinned to 9.3.0, so just use
  `./gradlew`. (Gradle 8.5 / Groovy 3 cannot run on Java 25.)
- Project Zomboid's `projectzomboid.jar` (used for `compileOnly`). By default
  `java/build.gradle` probes `/mnt/d/SteamLibrary/steamapps/common/ProjectZomboid`
  (WSL) then `D:/SteamLibrary/steamapps/common/ProjectZomboid` (Windows); set
  the `PZ_GAME_DIR` environment variable to override the location.

## Build (WSL)

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"   # provides Java 25
cd /mnt/c/Users/chris/Downloads/Puxadinho/java
./gradlew clean build
```

Output: `java/build/libs/Puxadinho.jar`

On Windows the same build works with `gradlew.bat build` as long as
`JAVA_HOME` points at a JDK 25.

## Install as a server agent

This is **not** a Project Zomboid mod. Only the jar is required.

1. Copy `Puxadinho.jar` to a stable location, e.g.
   `C:\Users\chris\Zomboid\Puxadinho.jar`.
2. Add `-javaagent` to the server launch command in
   `D:\SteamLibrary\steamapps\common\ProjectZomboid\ProjectZomboidServer.bat`:

```bat
@setlocal enableextensions
@cd /d "%~dp0"
SET _JAVA_OPTIONS=
SET PZ_CLASSPATH=./;projectzomboid.jar
SET PUXADINHO_JAR=C:\Users\chris\Zomboid\Puxadinho.jar
".\jre64\bin\java.exe" -javaagent:"%PUXADINHO_JAR%" --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -XX:+UseZGC -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -Xmx3072m -XX:+UseZGC -Djava.library.path=./natives/;./natives/win64/;./ -cp %PZ_CLASSPATH% zombie.network.GameServer
PAUSE
```

3. Restart the dedicated server.

Note: Steam updates may overwrite `ProjectZomboidServer.bat`; re-add the
`-javaagent` line after a game update if needed.

## Verify

With `DebugLogging = true` in `Puxadinho.ini`, the first lines of
`Puxadinho-debug.log` (next to the loaded agent jar, and in
`%USERPROFILE%\Zomboid`) identify the build and the jar that was loaded:

```
[Puxadinho] === Puxadinho build <build> ===
[Puxadinho] agent code source: D:\...\Puxadinho.jar
[Puxadinho] agent jar fingerprint: Puxadinho.jar size=... sha256=...
```

If that fingerprint does not match the jar you deployed, the wrong agent is
being loaded (check the `-javaagent` argument). If a patch fails to transform a
class, the agent logs `[Puxadinho] PATCH FAILED <PatchName> for <class>:` and
continues with the remaining patches (any changes already applied by earlier
patches are kept). When `DebugLogging = false` the agent is silent except for
those errors.

## Adding a patch

1. Create `src/com/puxadinho/patches/<area>/<Name>Patch.java` implementing
   `Patch`.
2. Add `new com.puxadinho.patches.<area>.<Name>Patch()` to the list in
   `PatchTransformer`.
3. Keep any helper/state classes next to the patch so it stays self-contained.

## Layout

```
java/
  build.gradle
  gradlew / gradlew.bat
  src/com/puxadinho/
    Agent.java                          # premain, installs PatchTransformer
    Config.java                         # Puxadinho.ini loader/defaults
    Patch.java                          # patch interface
    PatchTransformer.java               # class -> matching patches (chained)
    asm/
      ClassWriters.java                 # loader-aware ClassWriter
    patches/
      ranch/
        RanchAnimalAgePatch.java
        RanchRespawnPatch.java
        RanchRespawnGuard.java
      stats/
        PlayerStatsPatch.java
        StatsGuard.java
      death/
        DeathMessagePatch.java
        DeathMessageGuard.java
      vehicles/
        VehicleRespawnPatch.java
        VehicleRespawnGuard.java
        VehicleSpawnSite.java            # picks a loaded predefined vehicle zone + tile
        VehicleZoneCache.java            # parses/caches IsoMetaGrid.vehiclesZones to a file
      perf/
        PerfPatch.java
        PerfGuard.java                   # samples JVM + game state into puxadinho_perf.db
      safehouse/
        SafehouseItemPatch.java
        SafehouseGuard.java
```
