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
    public static boolean vehicleRespawnOnDelete = true;
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

        # Enable or disable vehicle respawning entirely.
        #   true  = abandoned/destroyed vehicles are replaced with a fresh one.
        #   false = vanilla behaviour (vehicles are never auto-replaced).
        VehicleRespawnEnabled = true

        # How many in-game days a vehicle must have no player nearby before it is
        # deleted and a new vehicle of the same script is spawned in the same spot.
        #   7 = one in-game week.
        VehicleRespawnDays = 7

        # Radius, in chunks, that counts as a player being "near" a vehicle. The
        # abandonment timer only runs while no player is within this many chunks.
        #   1 chunk = 8 tiles, so 2 = 16 tiles.
        VehicleRespawnChunks = 2

        # Also replace vehicles that were destroyed or removed (explosions, debug
        # menu, admin /remove) instead of only long-abandoned ones.
        #   true  = destroyed vehicles respawn.
        #   false = only vehicles abandoned for VehicleRespawnDays respawn.
        VehicleRespawnOnDelete = true

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
        vehicleRespawnOnDelete = bool(properties, "VehicleRespawnOnDelete", vehicleRespawnOnDelete);
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
