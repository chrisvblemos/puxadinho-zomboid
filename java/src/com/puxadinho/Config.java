package com.puxadinho;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import zombie.ZomboidFileSystem;

public final class Config {
    public static boolean ranchRespawnEnabled = true;
    public static double ranchRespawnHours = 48.0;
    public static boolean vehicleRespawnEnabled = true;
    public static double vehicleRespawnDays = 7.0;
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
        RanchRespawnHours = 48

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
        VehicleRespawnDays = 7

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
                properties.load(in);
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
}
