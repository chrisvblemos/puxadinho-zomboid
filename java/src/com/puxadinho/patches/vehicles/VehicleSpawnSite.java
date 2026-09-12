package com.puxadinho.patches.vehicles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.characters.IsoPlayer;
import zombie.core.random.Rand;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.SafeHouse;
import zombie.network.ServerMap;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.VehicleType;

/**
 * Picks a spawn site from the map's predefined vehicle zones (see
 * {@link VehicleZoneCache}) and computes the vehicle's world position with the
 * same math vanilla {@code IsoChunk.AddVehicles_OnZone} uses: the cross axis is
 * centred on a tile, and the length axis is anchored half a vehicle length from
 * the zone's edge (N/S from the top/bottom, W/E from the left/right).
 */
public final class VehicleSpawnSite {
    private static final int MAX_ZONE_TRIES = 60;
    private static final int TILES_PER_ZONE = 30;
    private static final float TILE_EDGE = 0.005F;

    public final float x;
    public final float y;
    public final int z;
    public final String script;
    public final String vehicleType;
    public final String zoneName;
    public final IsoDirections dir;
    public final float baseQuality;

    private VehicleSpawnSite(float x, float y, int z, String script, String vehicleType, String zoneName, IsoDirections dir, float baseQuality) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.script = script;
        this.vehicleType = vehicleType;
        this.zoneName = zoneName;
        this.dir = dir;
        this.baseQuality = baseQuality;
    }

    public static VehicleSpawnSite find(IsoPlayer player) {
        if (player == null) {
            return null;
        }
        List<VehicleZoneCache.Zone> all = VehicleZoneCache.zones();
        if (all.isEmpty()) {
            Debug.logGameplay("vehicle scan near " + playerName(player) + ": no predefined vehicle zones cached yet");
            return null;
        }

        int minDistance = Math.max(1, Config.vehicleSpawnMinDistance);
        int maxDistance = Math.max(minDistance + 1, Config.vehicleSpawnMaxDistance);
        double maxDistanceSq = (double)maxDistance * maxDistance;
        int px = (int)Math.floor(player.getX());
        int py = (int)Math.floor(player.getY());

        int noType = 0;
        int tooFar = 0;
        ArrayList<VehicleZoneCache.Zone> candidates = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            VehicleZoneCache.Zone zone = all.get(i);
            if (!VehicleType.hasTypeForZone(zone.name)) {
                noType++;
                continue;
            }
            if (zone.distanceSq(px, py) > maxDistanceSq) {
                tooFar++;
                continue;
            }
            candidates.add(zone);
        }

        if (candidates.isEmpty()) {
            Debug.logGameplay("vehicle scan near " + playerName(player) + ": no zone in range"
                + " [zones=" + all.size() + ", noDefinition=" + noType + ", outOfRange=" + tooFar + "]");
            return null;
        }

        Collections.shuffle(candidates);
        int tries = Math.min(candidates.size(), MAX_ZONE_TRIES);
        int tilesBlocked = 0;
        int tilesUnloaded = 0;
        int tilesTooClose = 0;
        int noScript = 0;
        for (int i = 0; i < tries; i++) {
            VehicleZoneCache.Zone zone = candidates.get(i);
            IsoDirections dir = directionFor(zone);
            boolean horizontal = dir == IsoDirections.E || dir == IsoDirections.W;
            for (int t = 0; t < TILES_PER_ZONE; t++) {
                VehicleType type = VehicleType.getRandomVehicleType(zone.name);
                if (type == null) {
                    noScript++;
                    continue;
                }
                String script = pickScript(type);
                if (script == null) {
                    noScript++;
                    continue;
                }
                VehicleScript vehicleScript = ScriptManager.instance == null ? null : ScriptManager.instance.getVehicle(script);
                if (vehicleScript == null || vehicleScript.getExtents() == null) {
                    noScript++;
                    continue;
                }
                float extentZ = vehicleScript.getExtents().z;
                float[] pos = placement(zone, dir, horizontal, extentZ);
                if (pos == null) {
                    break; // zone too small on the cross axis
                }
                float sx = pos[0];
                float sy = pos[1];
                double dx = player.getX() - sx;
                double dy = player.getY() - sy;
                if (dx * dx + dy * dy < (double)minDistance * minDistance) {
                    tilesTooClose++;
                    continue;
                }
                int cx = (int)Math.floor(sx);
                int cy = (int)Math.floor(sy);
                IsoGridSquare square = ServerMap.instance.getGridSquare(cx, cy, zone.z);
                if (square == null) {
                    tilesUnloaded++;
                    continue;
                }
                if (!isSpotClear(square, horizontal, player)) {
                    tilesBlocked++;
                    continue;
                }
                return new VehicleSpawnSite(sx, sy, zone.z, script, type.name, zone.name, dir, type.baseVehicleQuality);
            }
        }

        Debug.logGameplay("vehicle scan near " + playerName(player) + ": no clear tile"
            + " [candidateZones=" + candidates.size() + ", tilesTooClose=" + tilesTooClose
            + ", tilesUnloaded=" + tilesUnloaded + ", tilesBlocked=" + tilesBlocked + ", noScript=" + noScript + "]");
        return null;
    }

    /**
     * Vanilla placement: pick a tile on the cross axis (within the zone
     * interior) and anchor the length axis half a vehicle length from the zone
     * edge the vehicle faces away from.
     */
    private static float[] placement(VehicleZoneCache.Zone zone, IsoDirections dir, boolean horizontal, float extentZ) {
        int crossMin;
        int crossMax;
        if (horizontal) {
            if (zone.h < 3) {
                return null;
            }
            crossMin = zone.y + 1;
            crossMax = zone.y + zone.h - 2;
        } else {
            if (zone.w < 3) {
                return null;
            }
            crossMin = zone.x + 1;
            crossMax = zone.x + zone.w - 2;
        }
        if (crossMax < crossMin) {
            return null;
        }
        int cross = crossMin + Rand.Next(crossMax - crossMin + 1);
        float half = Math.max(0.0F, extentZ / 2.0F);
        float sx;
        float sy;
        switch (dir) {
            case N:
                sx = cross + 0.5F;
                sy = zone.y + half + 0.5F;
                break;
            case S:
                sx = cross + 0.5F;
                sy = zone.y + zone.h - half - 0.5F;
                break;
            case W:
                sx = zone.x + half + 0.5F;
                sy = cross + 0.5F;
                break;
            case E:
                sx = zone.x + zone.w - half - 0.5F;
                sy = cross + 0.5F;
                break;
            default:
                sx = cross + 0.5F;
                sy = zone.y + zone.h / 2.0F;
                break;
        }
        int cx = (int)Math.floor(sx);
        int cy = (int)Math.floor(sy);
        sx = clamp(sx, cx + TILE_EDGE, cx + 1.0F - TILE_EDGE);
        sy = clamp(sy, cy + TILE_EDGE, cy + 1.0F - TILE_EDGE);
        return new float[] {sx, sy};
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static String playerName(IsoPlayer player) {
        try {
            String name = player.getUsername();
            return name == null ? "?" : name;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String pickScript(VehicleType type) {
        ArrayList<VehicleType.VehicleTypeDefinition> definitions = type.vehiclesDefinition;
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        float roll = Rand.Next(0.0F, 100.0F);
        float cumulative = 0.0F;
        VehicleType.VehicleTypeDefinition chosen = null;
        for (int i = 0; i < definitions.size(); i++) {
            VehicleType.VehicleTypeDefinition definition = definitions.get(i);
            cumulative += definition.spawnChance;
            chosen = definition;
            if (roll < cumulative) {
                break;
            }
        }
        return chosen == null ? null : chosen.vehicleType;
    }

    private static IsoDirections directionFor(VehicleZoneCache.Zone zone) {
        if (zone.dir != null) {
            return zone.dir;
        }
        if (zone.w <= zone.h) {
            return Rand.Next(2) == 0 ? IsoDirections.N : IsoDirections.S;
        }
        return Rand.Next(2) == 0 ? IsoDirections.E : IsoDirections.W;
    }

    private static boolean isSpotClear(IsoGridSquare square, boolean horizontal, IsoPlayer player) {
        int xMin;
        int xMax;
        int yMin;
        int yMax;
        if (horizontal) {
            xMin = -2;
            xMax = 2;
            yMin = -1;
            yMax = 1;
        } else {
            xMin = -1;
            xMax = 1;
            yMin = -2;
            yMax = 2;
        }

        for (int ox = xMin; ox <= xMax; ox++) {
            for (int oy = yMin; oy <= yMax; oy++) {
                IsoGridSquare neighbour = ServerMap.instance.getGridSquare(square.getX() + ox, square.getY() + oy, square.getZ());
                if (neighbour == null) {
                    return false;
                }
                if (neighbour.isVehicleIntersecting()) {
                    return false;
                }
                if (neighbour.HasTree()) {
                    return false;
                }
                if (!neighbour.isFree(false)) {
                    return false;
                }
                if (neighbour.getRoom() != null) {
                    return false;
                }
                if (SafeHouse.getSafeHouse(neighbour) != null) {
                    return false;
                }
            }
        }
        return true;
    }
}
