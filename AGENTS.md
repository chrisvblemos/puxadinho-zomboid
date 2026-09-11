# Puxadinho

A server-side Project Zomboid Build 42 Java agent containing small, isolated
patches. It is packaged as a **plain Java agent** — no mod framework, no
ZombieBuddy, no `mod.info`.

Current patches:

1. **Zombie duplication** — the population manager accumulates duplicate records
   for the same zombie, producing identical outfit/loot clones that respawn and
   snowball into hundreds of zombies.
2. **Ranch animals die off in old worlds** — once the world passes ~60 days,
   ranch-spawned animals are increasingly killed on spawn; past ~190/250 days
   the chance becomes 100%, so no live animals appear.
3. **Player stat tracking** — persists a time-series of every survivor's
   progress (kills, hours survived, logon times, position, health, infection,
   inventory and skills) to a SQLite database that survives character death.
4. **World respawn** — refills a ranch's animals once its herd has been wiped
   out for 48 in-game hours, and deletes/refreshes vehicles that have had no
   player within 2 chunks for 7 in-game days (or were removed).
5. **Safehouse item protection** — stops the sandbox dropped-item removal timer
   from deleting world items whose square is inside a safehouse.

## Architecture

The agent uses `java.lang.instrument` (`premain`) and a single
`ClassFileTransformer`. Every fix is a self-contained `Patch`:

```java
public interface Patch {
    boolean matches(String className);
    byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception;
}
```

`PatchTransformer` holds the list of patches and dispatches each loaded class to
the first patch that claims it. Adding a fix means adding one file and one line
in `PatchTransformer`; patches never touch each other's code. The only bundled
library is ASM (`org.ow2.asm:asm`), shaded into the agent jar.

### Patch 1 — zombie duplication (`patches/zombie`)

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.popman.ZombiePopulationManager` | `removeChunkFromWorld` | marks every zombie in the unloading chunk as already registered |
| `zombie.popman.ZombiePopulationManager` | `virtualizeZombie` | if already registered, removes it from the world and skips `n_addZombie`; otherwise marks it |
| `zombie.popman.ZombiePopulationManager` | `addZombieStanding` / `addZombieMoving` | drops a population record if the same tile + direction + `persistentOutfitID` was already emitted in the current window |
| `zombie.characters.IsoZombie` | `resetForReuse` | clears the registration mark when the object is recycled |

Result: the population manager can never hold more than one record per zombie,
and can never instantiate two zombies for the same identity. Existing saved
duplicates collapse as their records are drained on load.

### Patch 2 — ranch animal age die-off (`patches/ranch`)

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

### Patch 3 — player stat tracking (`patches/stats`)

The vanilla server keeps one live row per character in `players.db` and deletes
it when the character dies, so there is no history. This patch samples every
online survivor and appends immutable snapshots to a separate SQLite database,
`puxadinho_stats.db`, created next to `players.db` in the current save.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.savefile.ServerPlayerDB` | `serverUpdateNetworkCharacter` | queues a snapshot each time the server saves a player (every ~3 min per player, plus world saves, login/character creation and disconnect) |
| `zombie.characters.IsoPlayer` | `onKilled` | queues a final snapshot plus a `player_deaths` row (killer, weapon, illness cause, PvP flag) and announces the cause to server chat for both PvP and non-PvP deaths |
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
  `poison`/`thirst`/`hunger`/`sickness`/`environment`), `weapon`, `cause`, a
  `pvp` flag and the same seven illness columns. Every player death is also
  announced to server chat as `<name> <cause>`.

For illness deaths (no attacker), `killer_type` is inferred from
`BodyDamage.isInfected()` / `ZOMBIE_INFECTION` (zombie virus), then wound
infection, food sickness, poison, thirst, hunger and general sickness. Because
the same stats are stored on every snapshot, infection progression is visible
over time, not just at the moment of death.

Because history is append-only, the data survives death and respawn and can
answer "what was this player doing yesterday". If the database cannot be
opened the patch logs once and drops snapshots rather than affecting the game.

### Patch 4 — world respawn (`patches/respawn`)

Dedicated servers never refill a wiped ranch, and abandoned/removed vehicles
stay gone or accumulate. This patch tracks both in a small SQLite database,
`puxadinho_world.db`, beside the save, and refreshes them server-side.

| Class | Method | Injected behavior |
|-------|--------|-------------------|
| `zombie.iso.areas.DesignationZone` | `update` | throttled scan of animal zones; a ranch with no live animals for 48 in-game hours gets `RandomizedRanchBase.randomizeRanch(zone, dzone)` re-run on its existing zone/dzone (never `checkRanchStory`, which would duplicate the zone) |
| `zombie.vehicles.VehicleManager` | `serverUpdate` | throttled (1 s) scan of loaded vehicles; records each vehicle's script/position by `sqlId`, updates `last_seen` while a player is within 2 chunks, and refreshes (`permanentlyRemove` + respawn same script at the same spot) once `last_seen` is 7 in-game days old |
| `zombie.vehicles.BaseVehicle` | `permanentlyRemove` | queues a same-script respawn at the recorded location when a vehicle is deleted by anything other than our own refresh |

Respawn reuses the vanilla `/addvehicle` path: `new BaseVehicle(cell)`,
`setScriptName`, position, `setSquare`, `chunk.vehicles.add`, `addToWorld`,
`VehiclesDB2.addVehicle`, key and `repair`. This gives correct multiplayer sync
through the normal `VehicleFullUpdate` stream. State is stored in
`ranch(x, y, z, zero_hour)` and `vehicles(sql_id, script, x, y, z, last_seen)`.

Known limits: the game clock pauses on an empty server (`PauseEmpty`), so
timers only advance while someone is online; map-placed burnt/smashed wrecks
are not treated as "destroyed"; and an admin `/remove vehicles` will be
replaced like any other deletion. Vehicle behavior needs live-server testing.

### Patch 5 — safehouse item protection (`patches/safehouse`)

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

## Configuration

On first use the agent writes `Puxadinho.ini` to the Zomboid cache
directory (`%USERPROFILE%\Zomboid\Puxadinho.ini` on Windows, next to the
`Saves` folder) and reads it back on every fresh server start. Delete it to
regenerate the defaults.

```ini
RanchRespawnEnabled = true
RanchRespawnHours = 48

VehicleRespawnEnabled = true
VehicleRespawnDays = 7
VehicleRespawnChunks = 2
VehicleRespawnOnDelete = true

StatsEnabled = true

SafehouseItemProtection = true

DebugLogging = false
```

`Config.load()` is lazy and safe to call from any patch; it no-ops until
`ZomboidFileSystem` is ready. Respawn times are in-world hours/days, so the
same `PauseEmpty` caveat applies. `VehicleRespawnChunks` is the radius (1
chunk = 8 tiles) that counts as a player being near a vehicle;
`VehicleRespawnOnDelete` toggles whether destroyed/removed vehicles are
replaced. `StatsEnabled` gates patch 3.

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
being loaded (check the `-javaagent` argument). If a class fails to transform,
the agent logs `[Puxadinho] PATCH FAILED <PatchName> for <class>:` and leaves
that class untouched. When `DebugLogging = false` the agent is silent except
for those errors.

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
    PatchTransformer.java               # class -> patch dispatch
    asm/
      ClassWriters.java                 # loader-aware ClassWriter
    patches/
      zombie/
        ZombieDuplicationPatch.java
        ZombieGuard.java
      ranch/
        RanchAnimalAgePatch.java
      stats/
        PlayerStatsPatch.java
        StatsGuard.java
      respawn/
        WorldRespawnPatch.java
        RespawnGuard.java
      safehouse/
        SafehouseItemPatch.java
        SafehouseGuard.java
```
