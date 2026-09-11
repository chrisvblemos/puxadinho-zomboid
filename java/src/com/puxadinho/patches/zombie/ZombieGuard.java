package com.puxadinho.patches.zombie;

import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ZombieGuard {
    private static final Set<IsoZombie> REGISTERED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Long> EMITTED = new HashSet<>();
    private static long emittedSlice;

    private ZombieGuard() {
    }

    public static synchronized boolean isRegistered(IsoZombie zombie) {
        return REGISTERED.contains(zombie);
    }

    public static synchronized void mark(IsoZombie zombie) {
        REGISTERED.add(zombie);
    }

    public static synchronized void clear(IsoZombie zombie) {
        REGISTERED.remove(zombie);
    }

    public static synchronized void markChunk(IsoChunk chunk) {
        for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    IsoGridSquare square = chunk.getGridSquare(x, y, z);
                    if (square == null || square.getMovingObjects().isEmpty()) {
                        continue;
                    }
                    for (int i = 0; i < square.getMovingObjects().size(); i++) {
                        if (square.getMovingObjects().get(i) instanceof IsoZombie zombie && eligible(zombie)) {
                            REGISTERED.add(zombie);
                        }
                    }
                }
            }
        }
    }

    public static synchronized boolean duplicate(float x, float y, float z, IsoDirections dir, int descriptor) {
        IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), PZMath.fastfloor(z));
        if (square == null || !(square.solidFloorCached ? square.solidFloor : square.TreatAsSolidFloor())) {
            return false;
        }
        long now = System.currentTimeMillis() / 100L;
        if (now != emittedSlice) {
            emittedSlice = now;
            EMITTED.clear();
        }
        return !EMITTED.add(key(x, y, z, dir == null ? 0 : dir.ordinal(), descriptor));
    }

    private static boolean eligible(IsoZombie zombie) {
        return !zombie.isReanimatedPlayer() && (!GameServer.server || !zombie.indoorZombie) && !zombie.isDead();
    }

    private static long key(float x, float y, float z, int dir, int descriptor) {
        long hash = descriptor;
        hash = hash * 31 + dir;
        hash = hash * 31 + (int)Math.floor(x);
        hash = hash * 31 + (int)Math.floor(y);
        hash = hash * 31 + (int)Math.floor(z);
        return hash;
    }
}
