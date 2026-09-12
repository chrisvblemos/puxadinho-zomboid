package com.puxadinho.patches.ranch;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.iso.IsoMetaChunk;
import zombie.iso.IsoWorld;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.zones.Zone;
import zombie.network.GameServer;
import zombie.randomizedWorld.randomizedRanch.RandomizedRanchBase;

/**
 * Refills a ranch's animals once its herd has been wiped out for
 * {@code RanchRespawnHours}. State lives in its own SQLite database,
 * {@code puxadinho_ranch.db}, beside the save.
 */
public final class RanchRespawnGuard {
    private static final HashMap<ZoneKey, Zone> RANCH_ZONES = new HashMap<>();
    private static final HashMap<ZoneKey, Double> RANCH_ZERO = new HashMap<>();
    private static Connection conn;
    private static boolean loaded;
    private static long lastZoneTick;

    private RanchRespawnGuard() {
    }

    public static void tickZones() {
        if (!GameServer.server) {
            return;
        }
        Config.load();
        if (!Config.ranchRespawnEnabled) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastZoneTick < 2500L) {
            return;
        }
        lastZoneTick = nowMs;
        open();
        double now = GameTime.getInstance().getWorldAgeHours();
        for (DesignationZoneAnimal zone : DesignationZoneAnimal.getAllZones()) {
            if (!"AnimalZone".equals(zone.type)) {
                continue;
            }
            Zone mapZone = findRanchZone(zone);
            if (mapZone == null) {
                continue;
            }
            ZoneKey key = new ZoneKey(zone.x, zone.y, zone.z);
            if (!zone.getAnimalsConnected().isEmpty()) {
                if (RANCH_ZERO.remove(key) != null) {
                    deleteRanch(key);
                }
                continue;
            }
            Double zero = RANCH_ZERO.get(key);
            if (zero == null) {
                RANCH_ZERO.put(key, now);
                putRanch(key, now);
                Debug.logGameplay("ranch " + key.x() + "," + key.y() + " emptied; respawn after " + (int)Config.ranchRespawnHours + "h");
                continue;
            }
            if (now - zero < Config.ranchRespawnHours) {
                continue;
            }
            if (!zone.isFullyStreamed()) {
                continue;
            }
            try {
                RandomizedRanchBase.randomizeRanch(mapZone, zone);
                zone.check();
            } catch (Throwable t) {
                Debug.error("ranch respawn failed: " + t);
            }
            if (!zone.getAnimalsConnected().isEmpty()) {
                Debug.logGameplay("ranch " + key.x() + "," + key.y() + " respawned " + zone.getAnimalsConnected().size() + " animals");
                RANCH_ZERO.remove(key);
                deleteRanch(key);
            } else {
                RANCH_ZERO.put(key, now);
                putRanch(key, now);
            }
        }
    }

    private static Zone findRanchZone(DesignationZoneAnimal dzone) {
        ZoneKey key = new ZoneKey(dzone.x, dzone.y, dzone.z);
        Zone zone = RANCH_ZONES.get(key);
        if (zone != null) {
            return zone;
        }
        IsoMetaChunk chunk = IsoWorld.instance.metaGrid.getChunkDataFromTile(dzone.x, dzone.y);
        if (chunk != null) {
            for (int i = 0; i < chunk.getZonesSize(); i++) {
                Zone candidate = chunk.getZone(i);
                if (candidate != null
                    && "Ranch".equals(candidate.getType())
                    && dzone.x >= candidate.x
                    && dzone.x < candidate.x + candidate.getWidth()
                    && dzone.y >= candidate.y
                    && dzone.y < candidate.y + candidate.getHeight()
                    && candidate.z == dzone.z) {
                    RANCH_ZONES.put(key, candidate);
                    return candidate;
                }
            }
        }
        return null;
    }

    private static void open() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Class.forName("org.sqlite.JDBC");
            String dir = ZomboidFileSystem.instance.getCurrentSaveDir();
            new File(dir).mkdirs();
            String path = dir + File.separator + "puxadinho_ranch.db";
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement stat = conn.createStatement()) {
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS ranch (x INTEGER, y INTEGER, z INTEGER, zero_hour REAL, PRIMARY KEY (x, y, z))");
            }
            try (Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT x, y, z, zero_hour FROM ranch")) {
                while (rs.next()) {
                    RANCH_ZERO.put(new ZoneKey(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getDouble(4));
                }
            }
            Debug.logGameplay("ranch respawn state: " + path);
        } catch (Throwable t) {
            Debug.error("ranch respawn database unavailable: " + t);
            conn = null;
        }
    }

    private static void putRanch(ZoneKey key, double zero) {
        exec("INSERT OR REPLACE INTO ranch (x, y, z, zero_hour) VALUES (" + key.x() + ", " + key.y() + ", " + key.z() + ", " + zero + ")");
    }

    private static void deleteRanch(ZoneKey key) {
        exec("DELETE FROM ranch WHERE x = " + key.x() + " AND y = " + key.y() + " AND z = " + key.z());
    }

    private static void exec(String sql) {
        if (conn == null) {
            return;
        }
        try (Statement stat = conn.createStatement()) {
            stat.executeUpdate(sql);
        } catch (Throwable t) {
            Debug.error("ranch respawn write failed: " + t);
        }
    }

    private record ZoneKey(int x, int y, int z) {
    }
}