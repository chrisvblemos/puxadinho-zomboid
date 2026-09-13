package com.puxadinho.patches.vehicles;

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
import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.physics.WorldSimulation;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehiclesDB2;

/**
 * Ticket-based vehicle economy: removed/abandoned vehicles earn tickets, which
 * the server spends to spawn zone-appropriate vehicles near random players.
 * State lives in its own SQLite database, {@code puxadinho_vehicles.db}, beside
 * the save.
 */
public final class VehicleRespawnGuard {
    private static final HashMap<Integer, VehicleRecord> VEHICLES = new HashMap<>();
    private static Connection conn;
    private static boolean loaded;
    private static long lastVehicleTick;
    private static int tickets;
    private static double lastSpawnHours;
    private static int roundRobinIndex;
    private static long lastScheduleLogHour = Long.MIN_VALUE;

    private VehicleRespawnGuard() {
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
        ArrayList<IsoPlayer> players = GameServer.getPlayers();
        trackAndAgeVehicles(players, now);
        maybeSpawnVehicles(players, now);
    }

    /**
     * Stamps vehicles near players as "seen" and hands long-abandoned vehicles to
     * the janitor. Removal itself is done by {@code permanentlyRemove()}, which
     * the patch hook routes back into {@link #removed(BaseVehicle)} so the ticket
     * is granted in exactly one place.
     */
    private static void trackAndAgeVehicles(ArrayList<IsoPlayer> players, double now) {
        double abandonHours = Math.max(1.0, Config.vehicleRespawnDays) * 24.0;
        ArrayList<BaseVehicle> vehicles = new ArrayList<>(VehicleManager.instance.getVehicles());
        for (int i = 0; i < vehicles.size(); i++) {
            BaseVehicle vehicle = vehicles.get(i);
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
            record.x = vehicle.getX();
            record.y = vehicle.getY();
            record.z = vehicle.getZ();
            if (near(vehicle, players)) {
                record.lastSeen = now;
                if (now - record.lastPersist >= 1.0) {
                    record.lastPersist = now;
                    putVehicle(vehicle.sqlId, record);
                }
            } else if (now - record.lastSeen >= abandonHours) {
                Debug.logGameplay("vehicle janitor: removing abandoned " + vehicle.getScriptName()
                    + " at " + (int)vehicle.getX() + "," + (int)vehicle.getY());
                try {
                    vehicle.permanentlyRemove();
                } catch (Throwable t) {
                    Debug.error("vehicle janitor removal failed: " + t);
                }
            }
        }
    }

    private static void maybeSpawnVehicles(ArrayList<IsoPlayer> players, double now) {
        // Build/log the predefined-zone cache even before a spawn is due.
        VehicleZoneCache.zones();
        if (players == null || players.isEmpty()) {
            return;
        }
        double intervalHours = Config.vehicleSpawnFreqDays <= 0.0 ? 1.0 : Config.vehicleSpawnFreqDays * 24.0;
        if (lastSpawnHours > now) {
            Debug.logGameplay("vehicle spawn: stored last-spawn hour (" + fmt(lastSpawnHours)
                + ") is ahead of world age (" + fmt(now) + "); resetting schedule");
            lastSpawnHours = now - intervalHours;
            setState("last_spawn_hours", lastSpawnHours);
        }
        if (now - lastSpawnHours < intervalHours) {
            long hour = (long)now;
            if (hour != lastScheduleLogHour) {
                lastScheduleLogHour = hour;
                Debug.logGameplay("vehicle spawn: waiting " + fmt(now - lastSpawnHours)
                    + "h of " + fmt(intervalHours) + "h [tickets=" + tickets + ", players=" + players.size() + "]");
            }
            return;
        }
        lastSpawnHours = now;
        setState("last_spawn_hours", lastSpawnHours);

        int batch = Math.max(1, Config.vehicleSpawnBatchSize);
        int attempts = Config.vehicleTicketSystem ? Math.min(batch, tickets) : batch;
        Debug.logGameplay("vehicle spawn: schedule triggered (" + attempts + " attempt(s), tickets=" + tickets + ")");
        if (attempts <= 0) {
            return;
        }

        for (int i = 0; i < attempts; i++) {
            IsoPlayer player = players.get(Math.floorMod(roundRobinIndex, players.size()));
            roundRobinIndex++;
            if (player == null) {
                continue;
            }
            VehicleSpawnSite site = VehicleSpawnSite.find(player);
            if (site == null) {
                Debug.logGameplay("vehicle spawn: no valid site near " + player.getUsername());
                continue;
            }
            if (!spawnVehicle(site)) {
                continue;
            }
            if (Config.vehicleTicketSystem) {
                tickets = Math.max(0, tickets - 1);
                setState("tickets", tickets);
            }
            Debug.logGameplay("vehicle spawn: " + site.script + " (" + site.vehicleType + ") at "
                + site.x + "," + site.y + " dir " + site.dir + "; tickets left " + tickets);
        }
        setState("round_robin", roundRobinIndex);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    public static void removed(BaseVehicle vehicle) {
        if (!GameServer.server || vehicle == null) {
            return;
        }
        Config.load();
        if (!Config.vehicleRespawnEnabled) {
            return;
        }
        open();
        int id = vehicle.sqlId;
        if (id != -1) {
            VEHICLES.remove(id);
            deleteVehicle(id);
        }
        boolean burnt;
        try {
            burnt = vehicle.isBurnt();
        } catch (Throwable t) {
            burnt = false;
        }
        Debug.logGameplay("vehicle removed: " + vehicle.getScriptName() + " burnt=" + burnt
            + (id == -1 ? "" : " sqlId=" + id) + " (ticketSystem=" + Config.vehicleTicketSystem + ")");
        addTicket(1);
    }

    private static void addTicket(int amount) {
        if (!Config.vehicleTicketSystem) {
            return;
        }
        int max = Math.max(0, Config.vehicleMaxTickets);
        if (tickets >= max) {
            return;
        }
        tickets = Math.min(max, tickets + Math.max(1, amount));
        setState("tickets", tickets);
        Debug.logGameplay("vehicle ticket: +" + amount + ", balance " + tickets);
    }

    private static boolean spawnVehicle(VehicleSpawnSite site) {
        try {
            int cx = (int)Math.floor(site.x);
            int cy = (int)Math.floor(site.y);
            IsoGridSquare square = ServerMap.instance.getGridSquare(cx, cy, site.z);
            if (square == null || square.chunk == null) {
                return false;
            }
            BaseVehicle vehicle = new BaseVehicle(IsoWorld.instance.currentCell);
            vehicle.setScriptName(site.script);
            vehicle.setScript();
            vehicle.setZone(site.zoneName);
            vehicle.setVehicleType(site.vehicleType);
            vehicle.setDir(site.dir);
            float angle = site.dir.toAngle() + (float)Math.PI;
            while (angle > (float)(Math.PI * 2.0)) {
                angle -= (float)(Math.PI * 2.0);
            }
            vehicle.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
            vehicle.jniTransform.setRotation(vehicle.savedRot);
            vehicle.setX(site.x);
            vehicle.setY(site.y);
            vehicle.setZ(site.z);
            vehicle.jniTransform.origin.set(
                vehicle.getX() - WorldSimulation.instance.offsetX,
                vehicle.getZ(),
                vehicle.getY() - WorldSimulation.instance.offsetY);
            if (overlapsNearbyVehicle(vehicle, site)) {
                Debug.logGameplay("vehicle spawn: skipped " + site.script + " at " + site.x + "," + site.y
                    + " (overlaps an existing vehicle)");
                return false;
            }
            if (!IsoChunk.doSpawnedVehiclesInInvalidPosition(vehicle)) {
                return false;
            }
            vehicle.setSquare(square);
            square.chunk.vehicles.add(vehicle);
            vehicle.chunk = square.chunk;
            vehicle.addToWorld();
            VehiclesDB2.instance.addVehicle(vehicle);
            vehicle.repair();
            applySandboxCondition(vehicle);
            applySandboxFuel(vehicle);
            applyBatteryCharge(vehicle);
            applyVanillaCondition(vehicle, site.baseQuality);
            return true;
        } catch (Throwable t) {
            Debug.error("vehicle spawn failed: " + t);
            return false;
        }
    }

    /**
     * Rejects a spawn whose oriented body would collide with a nearby vehicle.
     * {@code testCollisionWithVehicle} uses the script extents and the rotation
     * set above, so this catches overlaps that the plain square check misses,
     * including vehicles spawned earlier in the same batch.
     */
    private static boolean overlapsNearbyVehicle(BaseVehicle vehicle, VehicleSpawnSite site) {
        try {
            ArrayList<BaseVehicle> others = new ArrayList<>(VehicleManager.instance.getVehicles());
            for (int i = 0; i < others.size(); i++) {
                BaseVehicle other = others.get(i);
                if (other == null || other == vehicle || other.isRemovedFromWorld()) {
                    continue;
                }
                if (Math.abs(other.getX() - site.x) > 12.0F || Math.abs(other.getY() - site.y) > 12.0F) {
                    continue;
                }
                if (vehicle.testCollisionWithVehicle(other)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Debug.error("vehicle overlap check failed: " + t);
        }
        return false;
    }

    /**
     * Sets every part's condition from the sandbox {@code CarGeneralCondition}
     * option: 1 very low, 2 low, 3 normal, 4 high, 5 very high. The blanket
     * {@code repair()} above leaves parts pristine, so this is what makes a
     * respawned vehicle honour the server's condition setting.
     */
    private static void applySandboxCondition(BaseVehicle vehicle) {
        int min;
        int max;
        switch (SandboxOptions.instance.carGeneralCondition.getValue()) {
            case 1 -> { min = 0; max = 25; }
            case 2 -> { min = 20; max = 50; }
            case 4 -> { min = 75; max = 100; }
            case 5 -> { min = 90; max = 100; }
            default -> { min = 60; max = 100; }
        }
        int count = vehicle.getPartCount();
        for (int i = 0; i < count; i++) {
            vehicle.getPartByIndex(i).setCondition(Rand.NextInclusive(min, max));
        }
    }

    /**
     * Fills the gas tank with the sandbox {@code ChanceHasGas} /
     * {@code InitialGas} logic from {@code Vehicles.Create.GasTank}: first roll
     * whether the car has any fuel, then pick an amount within the range the
     * {@code InitialGas} option dictates. {@code repair()} fills the tank to
     * capacity, so this is what makes a respawned vehicle honour the setting.
     */
    private static void applySandboxFuel(BaseVehicle vehicle) {
        VehiclePart tank = vehicle.getPartById("GasTank");
        if (tank == null) {
            return;
        }
        int capacity = tank.getContainerCapacity();
        if (capacity <= 0) {
            return;
        }
        int initialChance = 45;
        int chanceHasGas = SandboxOptions.instance.chanceHasGas.getValue();
        if (chanceHasGas == 1) {
            initialChance = 20;
        } else if (chanceHasGas == 3) {
            initialChance = 95;
        }
        int gas = 0;
        if (Rand.Next(100) <= initialChance) {
            int minGas = Rand.Next(3, capacity / 3);
            int maxGas = Rand.Next(capacity / 3, capacity / 2);
            switch (SandboxOptions.instance.initialGas.getValue()) {
                case 1 -> {
                    minGas = 1;
                    maxGas = Rand.Next(2, capacity / 5);
                }
                case 2 -> {
                    minGas = 1;
                    maxGas = Rand.Next(4, capacity / 4);
                }
                case 4 -> {
                    minGas = Rand.Next(5, capacity / 2);
                    maxGas = Rand.Next(capacity / 2, capacity);
                }
                case 5 -> {
                    minGas = Rand.Next(8, capacity / 2);
                    maxGas = capacity;
                }
                default -> {
                }
            }
            gas = Rand.Next(minGas, maxGas);
        }
        tank.setContainerContentAmount(gas);
    }

    /**
     * Sets the battery's charge to its rolled part condition, so a respawned
     * car's battery is as depleted as its condition instead of always reading
     * 100% charge.
     */
    private static void applyBatteryCharge(BaseVehicle vehicle) {
        VehiclePart battery = vehicle.getPartById("Battery");
        if (battery == null) {
            return;
        }
        InventoryItem item = battery.getInventoryItem();
        if (item != null) {
            item.setCurrentUsesFloat(battery.getCondition() / 100.0F);
        }
    }

    /**
     * Mirrors the vanilla world-gen rust roll based on the zone's
     * {@code baseVehicleQuality}. Part condition is handled separately by
     * {@link #applySandboxCondition(BaseVehicle)}.
     */
    private static void applyVanillaCondition(BaseVehicle vehicle, float baseQuality) {
        float quality = Math.min(100.0F, baseQuality * 120.0F);
        if (Rand.Next(100) < (100.0F - quality)) {
            vehicle.setRust(1.0F);
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
            String path = dir + File.separator + "puxadinho_vehicles.db";
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement stat = conn.createStatement()) {
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS vehicles (sql_id INTEGER PRIMARY KEY, script TEXT, x REAL, y REAL, z REAL, last_seen REAL)");
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value REAL)");
            }
            try (Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT sql_id, script, x, y, z, last_seen FROM vehicles")) {
                while (rs.next()) {
                    VEHICLES.put(rs.getInt(1), new VehicleRecord(rs.getString(2), rs.getFloat(3), rs.getFloat(4), rs.getFloat(5), rs.getDouble(6), 0.0));
                }
            }
            tickets = (int)getState("tickets", 0);
            lastSpawnHours = getState("last_spawn_hours", 0);
            roundRobinIndex = (int)getState("round_robin", 0);
            Debug.logGameplay("vehicle respawn state: " + path + " (tickets=" + tickets + ")");
        } catch (Throwable t) {
            Debug.error("vehicle respawn database unavailable: " + t);
            conn = null;
        }
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

    private static double getState(String key, double fallback) {
        if (conn == null) {
            return fallback;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (Throwable t) {
            Debug.error("vehicle respawn state read failed: " + t);
        }
        return fallback;
    }

    private static void setState(String key, double value) {
        if (conn == null) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setDouble(2, value);
            ps.executeUpdate();
        } catch (Throwable t) {
            Debug.error("vehicle respawn state write failed: " + t);
        }
    }

    private static void exec(String sql) {
        if (conn == null) {
            return;
        }
        try (Statement stat = conn.createStatement()) {
            stat.executeUpdate(sql);
        } catch (Throwable t) {
            Debug.error("vehicle respawn write failed: " + t);
        }
    }

    private static final class VehicleRecord {
        private final String script;
        private float x;
        private float y;
        private float z;
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