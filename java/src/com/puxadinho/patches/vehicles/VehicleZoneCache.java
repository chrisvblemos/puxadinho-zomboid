package com.puxadinho.patches.vehicles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.puxadinho.Debug;

import zombie.ZomboidFileSystem;
import zombie.iso.IsoDirections;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.iso.zones.VehicleZone;

/**
 * The map's predefined vehicle zones.
 *
 * <p>Vehicle zones are declared in the map's {@code objects.lua} as
 * {@code { name = "...", type = "ParkingStall" | "Vehicle", x, y, z, width,
 * height }}. The engine parses them into {@link IsoMetaGrid#vehiclesZones}, so
 * the agent reads that list (which covers both zone types and any map/mods),
 * deduplicates it and writes it to {@code puxadinho_vehicle_zones.txt} beside
 * the save. Spawning then picks a zone directly instead of probing random
 * tiles. The file is also used as a fallback source if the engine list is not
 * ready.
 */
public final class VehicleZoneCache {
    public static final String FILE_NAME = "puxadinho_vehicle_zones.txt";

    public static final class Zone {
        public final String name;
        public final int x;
        public final int y;
        public final int z;
        public final int w;
        public final int h;
        public final IsoDirections dir;

        Zone(String name, int x, int y, int z, int w, int h, IsoDirections dir) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.dir = dir;
        }

        /** Squared distance from a point to the nearest edge of the rectangle. */
        public int distanceSq(int px, int py) {
            int nx = Math.max(x, Math.min(px, x + w - 1));
            int ny = Math.max(y, Math.min(py, y + h - 1));
            int dx = nx - px;
            int dy = ny - py;
            return dx * dx + dy * dy;
        }
    }

    private static List<Zone> zones;
    private static boolean initialized;
    private static int lastEngineSize = -1;

    private VehicleZoneCache() {
    }

    public static synchronized List<Zone> zones() {
        int engineSize = engineSize();
        if (initialized && engineSize == lastEngineSize) {
            return zones;
        }
        List<Zone> built = build();
        if (built.isEmpty()) {
            built = load();
        }
        if (built.isEmpty()) {
            // Map not loaded yet; retry on the next call.
            return new ArrayList<>();
        }
        zones = built;
        lastEngineSize = engineSize;
        initialized = true;
        save(zones);
        Debug.logGameplay("vehicle zones: cached " + zones.size() + " zones (" + fileNamePath() + ")");
        return zones;
    }

    private static int engineSize() {
        try {
            IsoMetaGrid meta = IsoWorld.instance == null ? null : IsoWorld.instance.metaGrid;
            return meta == null || meta.vehiclesZones == null ? -1 : meta.vehiclesZones.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static List<Zone> build() {
        List<Zone> list = new ArrayList<>();
        try {
            IsoMetaGrid meta = IsoWorld.instance == null ? null : IsoWorld.instance.metaGrid;
            if (meta == null || meta.vehiclesZones == null) {
                return list;
            }
            HashSet<String> seen = new HashSet<>();
            for (VehicleZone zone : meta.vehiclesZones) {
                if (zone == null) {
                    continue;
                }
                String name = zone.getName();
                if (name == null || name.isEmpty()) {
                    name = zone.getType();
                }
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String key = name + "@" + zone.getX() + "," + zone.getY() + "," + zone.getZ()
                    + "," + zone.getWidth() + "x" + zone.getHeight();
                if (seen.add(key)) {
                    list.add(new Zone(name, zone.getX(), zone.getY(), zone.getZ(), zone.getWidth(), zone.getHeight(), zone.dir));
                }
            }
        } catch (Throwable t) {
            Debug.error("vehicle zone build failed: " + t);
        }
        return list;
    }

    private static String fileNamePath() {
        File file = file();
        return file == null ? FILE_NAME : file.getAbsolutePath();
    }

    private static File file() {
        try {
            String dir = ZomboidFileSystem.instance.getCurrentSaveDir();
            if (dir == null) {
                return null;
            }
            new File(dir).mkdirs();
            return new File(dir, FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(List<Zone> list) {
        File file = file();
        if (file == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(file)) {
            String map = "?";
            try {
                map = IsoWorld.instance == null ? "?" : IsoWorld.instance.getMap();
            } catch (Throwable ignored) {
            }
            writer.write("# puxadinho vehicle zones; map=" + map + " count=" + list.size() + System.lineSeparator());
            writer.write("# name\tx\ty\tz\tw\th\tdir" + System.lineSeparator());
            for (Zone zone : list) {
                writer.write(zone.name + "\t" + zone.x + "\t" + zone.y + "\t" + zone.z
                    + "\t" + zone.w + "\t" + zone.h + "\t" + (zone.dir == null ? "" : zone.dir.name()) + System.lineSeparator());
            }
        } catch (Throwable t) {
            Debug.error("vehicle zone file write failed: " + t);
        }
    }

    private static List<Zone> load() {
        List<Zone> list = new ArrayList<>();
        File file = file();
        if (file == null || !file.exists()) {
            return list;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 6) {
                    continue;
                }
                try {
                    IsoDirections dir = null;
                    if (parts.length >= 7 && !parts[6].isEmpty()) {
                        try {
                            dir = IsoDirections.valueOf(parts[6]);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    list.add(new Zone(parts[0],
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), dir));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
            Debug.error("vehicle zone file read failed: " + t);
        }
        return list;
    }
}
