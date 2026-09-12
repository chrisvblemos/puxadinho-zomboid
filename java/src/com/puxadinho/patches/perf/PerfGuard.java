package com.puxadinho.patches.perf;

import java.io.File;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * Samples JVM health (heap, GC, CPU, threads) next to game state (online
 * players, loaded zombies/animals/vehicles, world age) so resource use can be
 * correlated with what the server was doing. Rows go to
 * {@code puxadinho_perf.db} beside the save, one per {@code PerfSampleSeconds}.
 */
public final class PerfGuard {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentLinkedQueue<Sample> QUEUE = new ConcurrentLinkedQueue<>();
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean RUNTIME = ManagementFactory.getRuntimeMXBean();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();
    private static final List<GarbageCollectorMXBean> GCS = ManagementFactory.getGarbageCollectorMXBeans();
    private static Connection conn;
    private static volatile boolean started;
    private static long lastSampleMs;
    private static long lastGcCount = -1L;
    private static long lastGcTime = -1L;

    private PerfGuard() {
    }

    public static void tick() {
        if (!GameServer.server) {
            return;
        }
        Config.load();
        if (!Config.perfStatsEnabled) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long interval = Math.max(1, Config.perfSampleSeconds) * 1000L;
        if (nowMs - lastSampleMs < interval) {
            return;
        }
        lastSampleMs = nowMs;
        try {
            QUEUE.add(sample(nowMs));
            start();
        } catch (Throwable t) {
            Debug.error("perf sample failed: " + t);
        }
    }

    private static Sample sample(long nowMs) {
        MemoryUsage heap = MEMORY.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY.getNonHeapMemoryUsage();
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : GCS) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) {
                gcCount += count;
            }
            if (time > 0) {
                gcTime += time;
            }
        }
        long countDelta = lastGcCount < 0 ? 0 : gcCount - lastGcCount;
        long timeDelta = lastGcTime < 0 ? 0 : gcTime - lastGcTime;
        lastGcCount = gcCount;
        lastGcTime = gcTime;
        double processCpu = -1.0;
        double systemCpu = -1.0;
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                processCpu = os.getProcessCpuLoad();
                systemCpu = os.getCpuLoad();
            }
        } catch (Throwable ignored) {
        }
        int players = 0;
        for (IsoPlayer player : GameServer.getPlayers()) {
            if (player != null && !player.isAnimal()) {
                players++;
            }
        }
        int zombies = 0;
        int animals = 0;
        int vehicles = 0;
        try {
            if (IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
                if (IsoWorld.instance.currentCell.getZombieList() != null) {
                    zombies = IsoWorld.instance.currentCell.getZombieList().size();
                }
                if (IsoWorld.instance.currentCell.getAnimals() != null) {
                    animals = IsoWorld.instance.currentCell.getAnimals().size();
                }
                if (IsoWorld.instance.currentCell.getVehicles() != null) {
                    vehicles = IsoWorld.instance.currentCell.getVehicles().size();
                }
            }
        } catch (Throwable ignored) {
        }
        double worldAge = 0.0;
        try {
            if (GameTime.getInstance() != null) {
                worldAge = GameTime.getInstance().getWorldAgeHours();
            }
        } catch (Throwable ignored) {
        }
        return new Sample(
            LocalDateTime.now().format(FMT),
            nowMs,
            RUNTIME.getUptime(),
            heap.getUsed(), heap.getCommitted(), heap.getMax(),
            nonHeap.getUsed(),
            gcCount, gcTime, countDelta, timeDelta,
            processCpu, systemCpu,
            THREADS.getThreadCount(), THREADS.getPeakThreadCount(),
            CLASSES.getLoadedClassCount(),
            players, zombies, animals, vehicles, worldAge
        );
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread writer = new Thread(PerfGuard::run, "Puxadinho-Perf");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PerfGuard::drain, "Puxadinho-Perf-Flush"));
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
        List<Sample> batch = new ArrayList<>();
        for (Sample item = QUEUE.poll(); item != null; item = QUEUE.poll()) {
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            for (Sample item : batch) {
                write(item);
            }
            conn.commit();
        } catch (Throwable t) {
            Debug.error("perf write failed: " + t);
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
            String path = dir + File.separator + "puxadinho_perf.db";
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement stat = conn.createStatement()) {
                stat.executeUpdate("CREATE TABLE IF NOT EXISTS perf_samples (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, epoch_ms INTEGER, uptime_ms INTEGER, heap_used INTEGER, heap_committed INTEGER, heap_max INTEGER, nonheap_used INTEGER, gc_count INTEGER, gc_time_ms INTEGER, gc_count_delta INTEGER, gc_time_delta_ms INTEGER, process_cpu_load REAL, system_cpu_load REAL, thread_count INTEGER, peak_thread_count INTEGER, loaded_classes INTEGER, players INTEGER, zombies INTEGER, animals INTEGER, vehicles INTEGER, world_age_hours REAL)");
                stat.executeUpdate("CREATE INDEX IF NOT EXISTS idx_perf_ts ON perf_samples (ts)");
            }
            conn.setAutoCommit(false);
            Debug.logGameplay("perf database: " + path);
            return true;
        } catch (Throwable t) {
            Debug.error("perf database unavailable: " + t);
            conn = null;
            return false;
        }
    }

    private static void write(Sample s) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO perf_samples (ts, epoch_ms, uptime_ms, heap_used, heap_committed, heap_max, nonheap_used, gc_count, gc_time_ms, gc_count_delta, gc_time_delta_ms, process_cpu_load, system_cpu_load, thread_count, peak_thread_count, loaded_classes, players, zombies, animals, vehicles, world_age_hours) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, s.ts());
            ps.setLong(2, s.epochMs());
            ps.setLong(3, s.uptimeMs());
            ps.setLong(4, s.heapUsed());
            ps.setLong(5, s.heapCommitted());
            ps.setLong(6, s.heapMax());
            ps.setLong(7, s.nonHeapUsed());
            ps.setLong(8, s.gcCount());
            ps.setLong(9, s.gcTimeMs());
            ps.setLong(10, s.gcCountDelta());
            ps.setLong(11, s.gcTimeDeltaMs());
            ps.setDouble(12, s.processCpuLoad());
            ps.setDouble(13, s.systemCpuLoad());
            ps.setInt(14, s.threadCount());
            ps.setInt(15, s.peakThreadCount());
            ps.setInt(16, s.loadedClasses());
            ps.setInt(17, s.players());
            ps.setInt(18, s.zombies());
            ps.setInt(19, s.animals());
            ps.setInt(20, s.vehicles());
            ps.setDouble(21, s.worldAgeHours());
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

    private record Sample(String ts, long epochMs, long uptimeMs, long heapUsed, long heapCommitted, long heapMax,
        long nonHeapUsed, long gcCount, long gcTimeMs, long gcCountDelta, long gcTimeDeltaMs, double processCpuLoad,
        double systemCpuLoad, int threadCount, int peakThreadCount, int loadedClasses, int players, int zombies,
        int animals, int vehicles, double worldAgeHours) {
    }
}
