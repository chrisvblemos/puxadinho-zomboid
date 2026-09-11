package com.puxadinho.patches.respawn;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaChunk;
import zombie.iso.IsoWorld;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.zones.Zone;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.randomizedWorld.randomizedRanch.RandomizedRanchBase;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclesDB2;

public final class RespawnGuard {
    private static final HashMap<ZoneKey, Zone> RANCH_ZONES = new HashMap<>();
    private static final HashMap<ZoneKey, Double> RANCH_ZERO = new HashMap<>();
    private static final HashMap<Integer, VehicleRecord> VEHICLES = new HashMap<>();
    private static final ArrayList<VehicleRecord> PENDING = new ArrayList<>();
    private static Connection conn;
    private static boolean loaded;
    private static boolean refreshing;
    private static long lastZoneTick;
    private static long lastVehicleTick;

    private RespawnGuard() {
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

    public static void tickVehicles() {
        if (!GameServer.server) {
            return;
        }
        Config.load();
        if (!Config.vehicleRespawnEnabled) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastVehicleTick < 1000L) {
            return;
        }
        lastVehicleTick = nowMs;
        open();
        double now = GameTime.getInstance().getWorldAgeHours();
        double vehicleHours = Config.vehicleRespawnDays * 24.0;
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            VehicleRecord record = PENDING.remove(i);
            spawnVehicle(record.script, record.x, record.y, record.z);
        }
        ArrayList<IsoPlayer> players = GameServer.getPlayers();
        ArrayList<BaseVehicle> vehicles = new ArrayList<>(VehicleManager.instance.getVehicles());
        for (BaseVehicle vehicle : vehicles) {
            if (vehicle == null || vehicle.sqlId == -1 || vehicle.serverRemovedFromWorld) {
                continue;
            }
            VehicleRecord record = VEHICLES.get(vehicle.sqlId);
            if (record == null) {
                record = new VehicleRecord(vehicle.getScriptName(), vehicle.getX(), vehicle.getY(), vehicle.getZ(), now, now);
                VEHICLES.put(vehicle.sqlId, record);
                putVehicle(vehicle.sqlId, record);
                continue;
            }
            if (!near(vehicle, players)) {
                continue;
            }
            if (now - record.lastSeen >= vehicleHours) {
                refresh(vehicle, record);
                continue;
            }
            record.lastSeen = now;
            if (now - record.lastPersist >= 1.0) {
                record.lastPersist = now;
                putVehicle(vehicle.sqlId, record);
            }
        }
    }

    public static void removed(BaseVehicle vehicle) {
        if (!GameServer.server || refreshing || vehicle == null) {
            return;
        }
        Config.load();
        if (!Config.vehicleRespawnEnabled || !Config.vehicleRespawnOnDelete) {
            return;
        }
        open();
        int id = vehicle.sqlId;
        if (id == -1) {
            return;
        }
        VehicleRecord record = VEHICLES.remove(id);
        deleteVehicle(id);
        if (record != null) {
            PENDING.add(record);
        }
    }

    private static void refresh(BaseVehicle vehicle, VehicleRecord record) {
        String script = record.script;
        float x = record.x;
        float y = record.y;
        float z = record.z;
        int id = vehicle.sqlId;
        VEHICLES.remove(id);
        deleteVehicle(id);
        refreshing = true;
        try {
            vehicle.permanentlyRemove();
        } catch (Throwable t) {
            Debug.error("vehicle removal failed: " + t);
        }
        refreshing = false;
        spawnVehicle(script, x, y, z);
    }

    private static void spawnVehicle(String script, float x, float y, float z) {
        try {
            IsoGridSquare square = ServerMap.instance.getGridSquare((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
            if (square == null || square.chunk == null) {
                return;
            }
            BaseVehicle vehicle = new BaseVehicle(IsoWorld.instance.currentCell);
            vehicle.setScriptName(script);
            vehicle.setX(x);
            vehicle.setY(y);
            vehicle.setZ(z);
            if (!IsoChunk.doSpawnedVehiclesInInvalidPosition(vehicle)) {
                return;
            }
            vehicle.setSquare(square);
            square.chunk.vehicles.add(vehicle);
            vehicle.chunk = square.chunk;
            vehicle.addToWorld();
            VehiclesDB2.instance.addVehicle(vehicle);
            vehicle.setCurrentKey(vehicle.createVehicleKey());
            vehicle.repair();
            Debug.logGameplay("respawned vehicle " + script + " at " + (int)x + "," + (int)y);
        } catch (Throwable t) {
            Debug.error("vehicle respawn failed: " + t);
        }
    }

    private static boolean near(BaseVehicle vehicle, ArrayList<IsoPlayer> players) {
        float range = Config.vehicleRespawnChunks * 8.0F;
        for (int i = 0; i < players.size(); i++) {
            IsoPlayer player = players.get(i);
            if (player != null
                && Math.abs(vehicle.getX() - player.getX()) < range
                && Math.abs(vehicle.getY() - player.getY()) < range) {
                return true;
            }
        }
        return false;
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
            String path = dir + File.separator + "puxadinho_world.db";
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement stat = conn.createStatement()) {
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS ranch (x INTEGER, y INTEGER, z INTEGER, zero_hour REAL, PRIMARY KEY (x, y, z))");
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS vehicles (sql_id INTEGER PRIMARY KEY, script TEXT, x REAL, y REAL, z REAL, last_seen REAL)");
            }
            try (Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT x, y, z, zero_hour FROM ranch")) {
                while (rs.next()) {
                    RANCH_ZERO.put(new ZoneKey(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getDouble(4));
                }
            }
            try (Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT sql_id, script, x, y, z, last_seen FROM vehicles")) {
                while (rs.next()) {
                    VEHICLES.put(rs.getInt(1), new VehicleRecord(rs.getString(2), rs.getFloat(3), rs.getFloat(4), rs.getFloat(5), rs.getDouble(6), 0.0));
                }
            }
            Debug.logGameplay("world respawn state: " + path);
        } catch (Throwable t) {
            Debug.error("world respawn database unavailable: " + t);
            conn = null;
        }
    }

    private static void putRanch(ZoneKey key, double zero) {
        exec("INSERT OR REPLACE INTO ranch (x, y, z, zero_hour) VALUES (" + key.x() + ", " + key.y() + ", " + key.z() + ", " + zero + ")");
    }

    private static void deleteRanch(ZoneKey key) {
        exec("DELETE FROM ranch WHERE x = " + key.x() + " AND y = " + key.y() + " AND z = " + key.z());
    }

    private static void putVehicle(int id, VehicleRecord record) {
        if (conn == null) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO vehicles (sql_id, script, x, y, z, last_seen) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, record.script);
            ps.setFloat(3, record.x);
            ps.setFloat(4, record.y);
            ps.setFloat(5, record.z);
            ps.setDouble(6, record.lastSeen);
            ps.executeUpdate();
        } catch (Throwable t) {
            Debug.error("vehicle state write failed: " + t);
        }
    }

    private static void deleteVehicle(int id) {
        exec("DELETE FROM vehicles WHERE sql_id = " + id);
    }

    private static void exec(String sql) {
        if (conn == null) {
            return;
        }
        try (Statement stat = conn.createStatement()) {
            stat.executeUpdate(sql);
        } catch (Throwable t) {
            Debug.error("world respawn write failed: " + t);
        }
    }

    private record ZoneKey(int x, int y, int z) {
    }

    private static final class VehicleRecord {
        private final String script;
        private final float x;
        private final float y;
        private final float z;
        private double lastSeen;
        private double lastPersist;

        private VehicleRecord(String script, float x, float y, float z, double lastSeen, double lastPersist) {
            this.script = script;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeen = lastSeen;
            this.lastPersist = lastPersist;
        }
    }
}
