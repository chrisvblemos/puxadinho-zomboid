package com.puxadinho;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import zombie.ZomboidFileSystem;

public final class Config {
    public static boolean ranchRespawnEnabled = true;
    public static double ranchRespawnHours = 168.0;
    public static boolean vehicleRespawnEnabled = true;
    public static double vehicleRespawnDays = 224.0;
    public static int vehicleRespawnChunks = 2;
    public static boolean vehicleTicketSystem = true;
    public static int vehicleMaxTickets = 30;
    public static double vehicleSpawnFreqDays = 1.0;
    public static int vehicleSpawnBatchSize = 5;
    public static int vehicleSpawnMinDistance = 55;
    public static int vehicleSpawnMaxDistance = 250;
    public static boolean safehouseItemProtection = true;
    public static int safehouseProtectionMargin = 0;
    public static boolean statsEnabled = true;
    public static boolean perfStatsEnabled = true;
    public static int perfSampleSeconds = 60;
    public static boolean deathMessagesEnabled = true;
    public static String deathMessagePlayer = "{player} foi morto por {killer} | {weapon_suffix} | {survived}";
    public static String deathMessageZombie = "{player} foi morto por {killer} | {survived}";
    public static String deathMessageAnimal = "{player} foi morto por {killer} | {survived}";
    public static String deathMessageFire = "{player} morreu queimado | {survived}";
    public static String deathMessageFall = "{player} foi morto pela gravidade | {survived}";
    public static String deathMessageInfection = "{player} virou zumbi | {survived}";
    public static String deathMessageWound = "{player} morreu de infeccao | {survived}";
    public static String deathMessageFood = "{player} morreu de intoxicacao alimentar | {survived}";
    public static String deathMessagePoison = "{player} morreu envenenado | {survived}";
    public static String deathMessageThirst = "{player} morreu de sede | {survived}";
    public static String deathMessageHunger = "{player} morreu de fome | {survived}";
    public static String deathMessageSickness = "{player} morreu de doenca | {survived}";
    public static String deathMessageEnvironment = "{player} morreu | {survived}";
    public static boolean debugLogging = false;

    private static final String DEFAULTS = """
        # ============================================================================
        # Puxadinho configuration. Restart the server for changes to take effect.
        # All times use the in-game world clock, which only advances while at least
        # one player is online (ServerOption PauseEmpty). 1 in-game day = 24 hours.
        # ============================================================================

        # ---------------------------- Ranch animals --------------------------------

        # Enable or disable ranch animal respawning entirely.
        #   true  = a wiped ranch is refilled automatically.
        #   false = vanilla behaviour (a wiped ranch stays empty forever).
        RanchRespawnEnabled = true

        # How many in-game hours a ranch must have zero live animals before its
        # herd is respawned. Lower means animals come back sooner.
        #   48 = two in-game days.
        RanchRespawnHours = 168

        # ------------------------------- Vehicles ----------------------------------
        #
        # Ticket-based vehicle respawn, modelled on the "VLCS HDRcade" mod:
        # removing a vehicle (burnt wreck removal, admin removal, a future
        # dismantle mod, or the abandonment janitor) adds a ticket; periodically
        # the server spends tickets to spawn a new zone-appropriate vehicle near a
        # random online player.

        # Enable or disable the vehicle system entirely.
        #   true  = abandoned/removed vehicles are recycled into new spawns.
        #   false = vanilla behaviour (vehicles are never auto-replaced).
        VehicleRespawnEnabled = true

        # How many in-game days a vehicle must have no player nearby before the
        # janitor removes it (the removal then grants a ticket).
        #   7 = one in-game week.
        VehicleRespawnDays = 224

        # Radius, in chunks, that counts as a player being "near" a vehicle. A
        # vehicle within this range is considered in use and is not aged out.
        #   1 chunk = 8 tiles, so 2 = 16 tiles.
        VehicleRespawnChunks = 2

        # Master switch for the ticket ledger.
        #   true  = spawns require and consume a ticket (tickets are earned from
        #           vehicle removals and capped by VehicleMaxTickets).
        #   false = spawns happen on schedule regardless (maintenance mode).
        VehicleTicketSystem = true

        # Highest number of stored tickets. Further removals while full are lost.
        VehicleMaxTickets = 30

        # How often the server spends tickets to spawn vehicles. 0 = once per
        # in-game hour. 1 = once per in-game day.
        VehicleSpawnFreqDays = 1

        # How many spawns to attempt per schedule tick.
        VehicleSpawnBatchSize = 5

        # Minimum distance, in tiles, between a spawned vehicle and the player it
        # is spawned near. The search then uses vanilla-style rings out to the
        # loaded chunks around that player.
        VehicleSpawnMinDistance = 55

        # Maximum distance, in tiles, from the chosen player to a predefined
        # vehicle zone. Spawns still require the zone chunk to be loaded.
        VehicleSpawnMaxDistance = 250

        # ------------------------------ Player stats -------------------------------

        # Master switch for the player statistics database (puxadinho_stats.db).
        #   true  = record kills, hours survived, logins, inventory, deaths, etc.
        #   false = no stat tracking and no database writes.
        StatsEnabled = true

        # ------------------------------ Performance --------------------------------

        # Master switch for the server performance database (puxadinho_perf.db).
        # Samples JVM health (heap use, GC count/time, CPU load, thread count)
        # alongside game state (online players, loaded zombies/animals/vehicles,
        # world age) so resource use can be correlated with what the server was
        # doing.
        #   true  = record a performance sample every PerfSampleSeconds.
        #   false = no performance sampling and no database writes.
        PerfStatsEnabled = true

        # Wall-clock seconds between performance samples. Smaller is finer-grained
        # but writes more rows.
        #   60 = one sample per minute.
        PerfSampleSeconds = 60

        # ---------------------------- Death messages -------------------------------

        # Announce every player death to server chat with a message chosen by the
        # cause of death. Each message is a template; the placeholders below are
        # replaced when the death is announced:
        #   {player}         = character name, plus the account username in
        #                      parentheses when known: "John Doe (steamuser)"
        #   {name}           = character name only
        #   {username}       = account username only (empty if unknown)
        #   {killer}         = killer or cause name ("a zombie", "an animal", a
        #                      player's character name; empty for other causes)
        #   {weapon}         = weapon name (empty when there is no weapon)
        #   {weapon_suffix}  = " (Weapon)" when a weapon was used, otherwise empty
        #   {survived}       = time the character survived, e.g. "7 days 18 hours"
        #                      or "1 month 2 days 3 hours" (a month is 30 days)
        #   {cause}          = machine cause key (see the list below)
        #
        # One message per cause key:
        #   DeathMessagePlayer      - killed by another player
        #   DeathMessageZombie      - killed by a zombie
        #   DeathMessageAnimal      - killed by an animal
        #   DeathMessageFire        - burned to death
        #   DeathMessageFall        - died in a fall
        #   DeathMessageInfection   - died of the zombie infection
        #   DeathMessageWound       - died of a wound infection
        #   DeathMessageFood        - died of food sickness
        #   DeathMessagePoison      - died of poison
        #   DeathMessageThirst      - died of thirst
        #   DeathMessageHunger      - died of hunger
        #   DeathMessageSickness    - died of general sickness
        #   DeathMessageEnvironment - any other cause
        #   true  = announce player deaths in server chat.
        #   false = no death announcements (statistics are unaffected).
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

        # ---------------------------- Safehouse items ------------------------------

        # Keep dropped world items from being deleted by the sandbox item-removal
        # timer while their square is inside a safehouse. Protection is written
        # into the saved chunk, so protected items survive even if the safehouse
        # is later deleted (by an admin or because the owner stayed away).
        #   true  = items inside safehouses are protected.
        #   false = vanilla behaviour (all old dropped items are removed).
        SafehouseItemProtection = true

        # Extra tiles around a safehouse that also count as protected. Use this if
        # your base (yard, porch, attached structure) extends beyond the claimed
        # building. 0 = strict safehouse bounds only.
        SafehouseProtectionMargin = 0

        # ------------------------------- Logging -----------------------------------

        # Master switch for all informational logging from every patch (safehouse
        # items, ranch/vehicle respawn, player stats, agent startup). When false the
        # agent is silent except for genuine errors, which always go to stderr.
        # The startup/build fingerprint is written once when this is true, so it can
        # be used to confirm the correct Puxadinho.jar was loaded.
        #   true  = verbose logging (useful for troubleshooting).
        #   false = quiet.
        DebugLogging = false
        """;

    private static boolean loaded;

    private Config() {
    }

    public static synchronized void load() {
        if (loaded || ZomboidFileSystem.instance == null) {
            return;
        }
        loaded = true;
        File file = new File(ZomboidFileSystem.instance.getCacheDir(), "Puxadinho.ini");
        Properties properties = new Properties();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Debug.error("failed to read " + file + ": " + t);
            }
        } else {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(DEFAULTS.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Debug.error("failed to create " + file + ": " + t);
            }
        }
        ranchRespawnEnabled = bool(properties, "RanchRespawnEnabled", ranchRespawnEnabled);
        ranchRespawnHours = number(properties, "RanchRespawnHours", ranchRespawnHours);
        vehicleRespawnEnabled = bool(properties, "VehicleRespawnEnabled", vehicleRespawnEnabled);
        vehicleRespawnDays = number(properties, "VehicleRespawnDays", vehicleRespawnDays);
        vehicleRespawnChunks = (int)number(properties, "VehicleRespawnChunks", vehicleRespawnChunks);
        vehicleTicketSystem = bool(properties, "VehicleTicketSystem", vehicleTicketSystem);
        vehicleMaxTickets = (int)number(properties, "VehicleMaxTickets", vehicleMaxTickets);
        vehicleSpawnFreqDays = number(properties, "VehicleSpawnFreqDays", vehicleSpawnFreqDays);
        vehicleSpawnBatchSize = (int)number(properties, "VehicleSpawnBatchSize", vehicleSpawnBatchSize);
        vehicleSpawnMinDistance = (int)number(properties, "VehicleSpawnMinDistance", vehicleSpawnMinDistance);
        vehicleSpawnMaxDistance = (int)number(properties, "VehicleSpawnMaxDistance", vehicleSpawnMaxDistance);
        safehouseItemProtection = bool(properties, "SafehouseItemProtection", safehouseItemProtection);
        safehouseProtectionMargin = (int)number(properties, "SafehouseProtectionMargin", safehouseProtectionMargin);
        statsEnabled = bool(properties, "StatsEnabled", statsEnabled);
        perfStatsEnabled = bool(properties, "PerfStatsEnabled", perfStatsEnabled);
        perfSampleSeconds = (int)number(properties, "PerfSampleSeconds", perfSampleSeconds);
        deathMessagesEnabled = bool(properties, "DeathMessagesEnabled", deathMessagesEnabled);
        deathMessagePlayer = string(properties, "DeathMessagePlayer", deathMessagePlayer);
        deathMessageZombie = string(properties, "DeathMessageZombie", deathMessageZombie);
        deathMessageAnimal = string(properties, "DeathMessageAnimal", deathMessageAnimal);
        deathMessageFire = string(properties, "DeathMessageFire", deathMessageFire);
        deathMessageFall = string(properties, "DeathMessageFall", deathMessageFall);
        deathMessageInfection = string(properties, "DeathMessageInfection", deathMessageInfection);
        deathMessageWound = string(properties, "DeathMessageWound", deathMessageWound);
        deathMessageFood = string(properties, "DeathMessageFood", deathMessageFood);
        deathMessagePoison = string(properties, "DeathMessagePoison", deathMessagePoison);
        deathMessageThirst = string(properties, "DeathMessageThirst", deathMessageThirst);
        deathMessageHunger = string(properties, "DeathMessageHunger", deathMessageHunger);
        deathMessageSickness = string(properties, "DeathMessageSickness", deathMessageSickness);
        deathMessageEnvironment = string(properties, "DeathMessageEnvironment", deathMessageEnvironment);
        debugLogging = bool(properties, "DebugLogging", debugLogging);
        Debug.setEnabled(debugLogging);
        Debug.logStartupOnce();
        Debug.logGameplay("config loaded: " + file
            + " (DebugLogging=" + debugLogging
            + ", SafehouseItemProtection=" + safehouseItemProtection
            + ", SafehouseProtectionMargin=" + safehouseProtectionMargin + ")");
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static double number(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String string(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }
}
