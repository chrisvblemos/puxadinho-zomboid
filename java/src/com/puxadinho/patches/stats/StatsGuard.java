package com.puxadinho.patches.stats;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.ZomboidFileSystem;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.characters.skills.PerkFactory;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.network.GameServer;

public final class StatsGuard {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentLinkedQueue<Object> QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, String> LOGONS = new ConcurrentHashMap<>();
    private static final HashMap<String, Long> IDS = new HashMap<>();
    private static Connection conn;
    private static volatile boolean started;

    private StatsGuard() {
    }

    public static void connected(UdpConnection connection) {
        Config.load();
        if (!GameServer.server || !Config.statsEnabled || connection == null) {
            return;
        }
        LOGONS.put(idKey(Core.gameSaveWorld, connection.getIDStr(), connection.getUserName()), now());
        start();
    }

    public static void capture(IsoPlayer player, UdpConnection connection) {
        Config.load();
        if (!GameServer.server || !Config.statsEnabled || player == null) {
            return;
        }
        try {
            QUEUE.add(snapshot(player, connection, now()));
            start();
        } catch (Throwable t) {
            Debug.error("stats capture failed: " + t);
        }
    }

    public static void killed(IsoPlayer victim, IsoGameCharacter killer, HandWeapon weapon) {
        Config.load();
        if (!GameServer.server || !Config.statsEnabled || victim == null) {
            return;
        }
        try {
            Snap snap = snapshot(victim, GameServer.getConnectionFromPlayer(victim), now());
            String killerUsername = "";
            String killerName = "";
            String killerType;
            if (killer instanceof IsoPlayer player) {
                killerType = "player";
                killerUsername = player.getUsername() == null ? "" : player.getUsername();
                killerName = name(player);
            } else if (killer instanceof IsoZombie) {
                killerType = "zombie";
                killerName = "a zombie";
            } else if (killer != null && killer.isAnimal()) {
                killerType = "animal";
                killerName = "an animal";
            } else if (victim.isOnFire()) {
                killerType = "fire";
                killerName = "fire";
            } else if (victim.isKilledByFall()) {
                killerType = "fall";
                killerName = "a fall";
            } else {
                killerType = illness(victim, snap.vitals());
                killerName = "";
            }
            String weaponName = weapon == null || weapon.getName() == null ? "" : weapon.getName();
            QUEUE.add(snap);
            QUEUE.add(new Death(
                snap.world(),
                snap.steamid(),
                snap.username(),
                snap.name(),
                snap.profession(),
                snap.ts(),
                snap.x(),
                snap.y(),
                snap.z(),
                killerName,
                killerUsername,
                killerType,
                weaponName,
                killerType,
                "player".equals(killerType) ? 1 : 0,
                snap.vitals()
            ));
            start();
        } catch (Throwable t) {
            Debug.error("death log failed: " + t);
        }
    }

    private static Snap snapshot(IsoPlayer player, UdpConnection connection, String ts) {
        String world = Core.gameSaveWorld;
        String steamid = connection != null && connection.getIDStr() != null ? connection.getIDStr() : Long.toString(player.getSteamID());
        String username = connection != null && connection.getUserName() != null ? connection.getUserName() : player.getUsername();
        if (steamid == null) {
            steamid = "";
        }
        if (username == null) {
            username = "";
        }
        return new Snap(
            world,
            steamid,
            username,
            name(player),
            String.valueOf(player.getDescriptor().getCharacterProfession()),
            LOGONS.get(idKey(world, steamid, username)),
            ts,
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getHoursSurvived(),
            player.getZombieKills(),
            player.getSurvivorKills(),
            player.getLastZombieKills(),
            player.getHealth(),
            player.getBodyDamage().isInfected() ? 1 : 0,
            player.isDead() ? 1 : 0,
            player.getInventory().getContentsWeight(),
            player.getInventory().getCapacityWeight(),
            inventory(player.getInventory()),
            skills(player),
            vitals(player)
        );
    }

    private static Vitals vitals(IsoPlayer player) {
        Stats stats = player.getStats();
        return new Vitals(
            stats.get(CharacterStat.ZOMBIE_INFECTION),
            stats.get(CharacterStat.FOOD_SICKNESS),
            stats.get(CharacterStat.POISON),
            player.getBodyDamage().getGeneralWoundInfectionLevel(),
            stats.get(CharacterStat.SICKNESS),
            stats.get(CharacterStat.HUNGER),
            stats.get(CharacterStat.THIRST)
        );
    }

    private static String illness(IsoPlayer victim, Vitals vitals) {
        if (victim.getBodyDamage().isInfected() || vitals.zombieInfection() >= 100.0F) {
            return "infection";
        }
        if (vitals.woundInfection() > 50.0F) {
            return "wound";
        }
        if (vitals.foodSickness() > 50.0F) {
            return "food";
        }
        if (vitals.poison() > 50.0F) {
            return "poison";
        }
        if (vitals.thirst() > 0.9F) {
            return "thirst";
        }
        if (vitals.hunger() > 0.9F) {
            return "hunger";
        }
        if (vitals.sickness() > 0.9F) {
            return "sickness";
        }
        return "environment";
    }

    private static String name(IsoPlayer player) {
        return player.getDescriptor().getForename() + " " + player.getDescriptor().getSurname();
    }

    private static String inventory(ItemContainer container) {
        HashMap<String, Integer> counts = new HashMap<>();
        collect(counts, container, 0);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append(entry.getKey()).append(':').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    private static void collect(HashMap<String, Integer> counts, ItemContainer container, int depth) {
        if (container == null || depth > 8) {
            return;
        }
        for (InventoryItem item : container.getItems()) {
            counts.merge(item.getFullType(), Math.max(1, item.getCount()), Integer::sum);
            if (item instanceof InventoryContainer nested) {
                collect(counts, nested.getItemContainer(), depth + 1);
            }
        }
    }

    private static String skills(IsoPlayer player) {
        StringBuilder sb = new StringBuilder();
        for (PerkFactory.Perk perk : player.getXp().xpMap.keySet()) {
            sb.append(perk.getId()).append(':').append(player.getPerkLevel(perk)).append(';');
        }
        return sb.toString();
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread writer = new Thread(StatsGuard::run, "Puxadinho-Stats");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(StatsGuard::drain, "Puxadinho-Stats-Flush"));
    }

    private static void run() {
        open();
        while (true) {
            if (QUEUE.isEmpty()) {
                sleep();
                continue;
            }
            drain();
        }
    }

    private static synchronized void drain() {
        if (conn == null && !open()) {
            QUEUE.clear();
            return;
        }
        List<Object> batch = new ArrayList<>();
        for (Object item = QUEUE.poll(); item != null; item = QUEUE.poll()) {
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            for (Object item : batch) {
                if (item instanceof Snap snap) {
                    write(snap);
                } else if (item instanceof Death death) {
                    writeDeath(death);
                }
            }
            conn.commit();
        } catch (Throwable t) {
            Debug.error("stats write failed: " + t);
            rollback();
        }
    }

    private static synchronized boolean open() {
        if (conn != null) {
            return true;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            String dir = ZomboidFileSystem.instance.getCurrentSaveDir();
            new File(dir).mkdirs();
            String path = dir + File.separator + "puxadinho_stats.db";
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement stat = conn.createStatement()) {
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS player_stats (id INTEGER PRIMARY KEY AUTOINCREMENT, world TEXT, steamid TEXT, username TEXT, name TEXT, profession TEXT, first_seen TEXT, last_seen TEXT, last_logon TEXT, UNIQUE(world, steamid, username, name))");
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS stat_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, player_id INTEGER, ts TEXT, x REAL, y REAL, z REAL, hours_survived REAL, zombie_kills INTEGER, survivor_kills INTEGER, last_zombie_kills INTEGER, health REAL, infected INTEGER, dead INTEGER, inv_weight REAL, inv_capacity REAL, inventory TEXT, skills TEXT)");
                stat.executeUpdate("CREATE INDEX IF NOT EXISTS idx_stat_player ON stat_snapshots (player_id, ts)");
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS player_deaths (id INTEGER PRIMARY KEY AUTOINCREMENT, player_id INTEGER, ts TEXT, x REAL, y REAL, z REAL, killer_name TEXT, killer_username TEXT, killer_type TEXT, weapon TEXT, cause TEXT, pvp INTEGER)");
                stat.executeUpdate("CREATE INDEX IF NOT EXISTS idx_death_player ON player_deaths (player_id, ts)");
            }
            migrate("stat_snapshots", VITALS);
            migrate("player_deaths", VITALS);
            conn.setAutoCommit(false);
            Debug.logGameplay("stats database: " + path);
            return true;
        } catch (Throwable t) {
            Debug.error("stats database unavailable: " + t);
            conn = null;
            return false;
        }
    }

    private static final String[] VITALS = {
        "zombie_infection REAL", "food_sickness REAL", "poison REAL", "wound_infection REAL", "sickness REAL", "hunger REAL", "thirst REAL"
    };

    private static void migrate(String table, String[] defs) {
        for (String def : defs) {
            String column = def.substring(0, def.indexOf(' '));
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + table + " LIMIT 0"); ResultSet rs = ps.executeQuery()) {
                rs.findColumn(column);
            } catch (Throwable missing) {
                try (Statement stat = conn.createStatement()) {
                    stat.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + def);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void write(Snap snap) throws Exception {
        long id = id(snap.world(), snap.steamid(), snap.username(), snap.name(), snap.profession(), snap.ts(), snap.logon());
        Vitals v = snap.vitals();
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO stat_snapshots (player_id, ts, x, y, z, hours_survived, zombie_kills, survivor_kills, last_zombie_kills, health, infected, dead, inv_weight, inv_capacity, inventory, skills, zombie_infection, food_sickness, poison, wound_infection, sickness, hunger, thirst) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setLong(1, id);
            ps.setString(2, snap.ts());
            ps.setFloat(3, snap.x());
            ps.setFloat(4, snap.y());
            ps.setFloat(5, snap.z());
            ps.setDouble(6, snap.hours());
            ps.setInt(7, snap.zk());
            ps.setInt(8, snap.sk());
            ps.setInt(9, snap.lzk());
            ps.setFloat(10, snap.health());
            ps.setInt(11, snap.infected());
            ps.setInt(12, snap.dead());
            ps.setFloat(13, snap.invWeight());
            ps.setFloat(14, snap.invCapacity());
            ps.setString(15, snap.inventory());
            ps.setString(16, snap.skills());
            ps.setFloat(17, v.zombieInfection());
            ps.setFloat(18, v.foodSickness());
            ps.setFloat(19, v.poison());
            ps.setFloat(20, v.woundInfection());
            ps.setFloat(21, v.sickness());
            ps.setFloat(22, v.hunger());
            ps.setFloat(23, v.thirst());
            ps.executeUpdate();
        }
    }

    private static void writeDeath(Death death) throws Exception {
        long id = id(death.world(), death.steamid(), death.username(), death.name(), death.profession(), death.ts(), null);
        Vitals v = death.vitals();
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO player_deaths (player_id, ts, x, y, z, killer_name, killer_username, killer_type, weapon, cause, pvp, zombie_infection, food_sickness, poison, wound_infection, sickness, hunger, thirst) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setLong(1, id);
            ps.setString(2, death.ts());
            ps.setFloat(3, death.x());
            ps.setFloat(4, death.y());
            ps.setFloat(5, death.z());
            ps.setString(6, death.killerName());
            ps.setString(7, death.killerUsername());
            ps.setString(8, death.killerType());
            ps.setString(9, death.weapon());
            ps.setString(10, death.cause());
            ps.setInt(11, death.pvp());
            ps.setFloat(12, v.zombieInfection());
            ps.setFloat(13, v.foodSickness());
            ps.setFloat(14, v.poison());
            ps.setFloat(15, v.woundInfection());
            ps.setFloat(16, v.sickness());
            ps.setFloat(17, v.hunger());
            ps.setFloat(18, v.thirst());
            ps.executeUpdate();
        }
    }

    private static long id(String world, String steamid, String username, String name, String profession, String ts, String logon) throws Exception {
        String key = idKey(world, steamid, username) + '\u0000' + name;
        Long cached = IDS.get(key);
        if (cached != null) {
            touch(cached, ts, logon, profession);
            return cached;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM player_stats WHERE world = ? AND steamid = ? AND username = ? AND name = ?")) {
            ps.setString(1, world);
            ps.setString(2, steamid);
            ps.setString(3, username);
            ps.setString(4, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    IDS.put(key, id);
                    touch(id, ts, logon, profession);
                    return id;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO player_stats (world, steamid, username, name, profession, first_seen, last_seen, last_logon) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, world);
            ps.setString(2, steamid);
            ps.setString(3, username);
            ps.setString(4, name);
            ps.setString(5, profession);
            ps.setString(6, ts);
            ps.setString(7, ts);
            ps.setString(8, logon);
            ps.executeUpdate();
        }
        long id;
        try (Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            id = rs.getLong(1);
        }
        IDS.put(key, id);
        return id;
    }

    private static void touch(long id, String ts, String logon, String profession) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE player_stats SET last_seen = ?, last_logon = COALESCE(?, last_logon), profession = ? WHERE id = ?")) {
            ps.setString(1, ts);
            ps.setString(2, logon);
            ps.setString(3, profession);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    private static void rollback() {
        try {
            conn.rollback();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String idKey(String world, String steamid, String username) {
        return world + '\u0000' + steamid + '\u0000' + username;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }

    private record Vitals(float zombieInfection, float foodSickness, float poison, float woundInfection,
        float sickness, float hunger, float thirst) {
    }

    private record Snap(String world, String steamid, String username, String name, String profession, String logon,
        String ts, float x, float y, float z, double hours, int zk, int sk, int lzk, float health, int infected,
        int dead, float invWeight, float invCapacity, String inventory, String skills, Vitals vitals) {
    }

    private record Death(String world, String steamid, String username, String name, String profession, String ts,
        float x, float y, float z, String killerName, String killerUsername, String killerType, String weapon,
        String cause, int pvp, Vitals vitals) {
    }
}
