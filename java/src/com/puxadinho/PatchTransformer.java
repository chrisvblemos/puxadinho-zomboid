package com.puxadinho;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.StringJoiner;

public final class PatchTransformer implements ClassFileTransformer {
    private static final List<Patch> PATCHES = List.of(
        new com.puxadinho.patches.zombie.ZombieDuplicationPatch(),
        new com.puxadinho.patches.ranch.RanchAnimalAgePatch(),
        new com.puxadinho.patches.stats.PlayerStatsPatch(),
        new com.puxadinho.patches.death.DeathMessagePatch(),
        new com.puxadinho.patches.ranch.RanchRespawnPatch(),
        new com.puxadinho.patches.vehicles.VehicleRespawnPatch(),
        new com.puxadinho.patches.safehouse.SafehouseItemPatch()
    );

    public static String patchNames() {
        StringJoiner joiner = new StringJoiner(", ");
        for (Patch patch : PATCHES) {
            joiner.add(patch.getClass().getSimpleName());
        }
        return joiner.toString();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] buffer) {
        if (className == null) {
            return null;
        }
        byte[] current = buffer;
        boolean changed = false;
        for (int i = 0; i < PATCHES.size(); i++) {
            Patch patch = PATCHES.get(i);
            if (!patch.matches(className)) {
                continue;
            }
            String patchName = patch.getClass().getSimpleName();
            try {
                Debug.log("transforming " + className + " with " + patchName
                    + " (" + current.length + " bytes in)");
                byte[] result = patch.transform(loader, className, current);
                if (result == null) {
                    continue;
                }
                Debug.log("transformed " + className + " with " + patchName
                    + " (" + current.length + " -> " + result.length + " bytes)");
                current = result;
                changed = true;
            } catch (Throwable t) {
                Debug.error("PATCH FAILED " + patchName + " for " + className + ": " + t);
            }
        }
        return changed ? current : null;
    }
}
