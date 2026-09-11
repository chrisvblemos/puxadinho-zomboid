package com.puxadinho.patches.safehouse;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.iso.IsoGridSquare;
import zombie.iso.areas.SafeHouse;
import zombie.iso.objects.IsoWorldInventoryObject;

public final class SafehouseGuard {
    private static final int SAFEHOUSE_LOG_LIMIT = 500;
    private static final int NEAR_LOG_LIMIT = 200;
    private static final int DETAIL_LOG_LIMIT = 10;

    private static boolean warnedEmpty;
    private static boolean dumped;
    private static int nearLogs;
    private static int protectedLogs;
    private static int insideLogs;
    private static int skipAlreadyLogs;
    private static int skipNullSquareLogs;
    private static int saveHookLogs;

    static {
        Debug.logGameplay("SafehouseGuard active (build " + Debug.build() + ")");
    }

    private SafehouseGuard() {
    }

    public static boolean inSafehouse(IsoWorldInventoryObject item) {
        Config.load();
        if (!Config.safehouseItemProtection || item == null) {
            return false;
        }
        if (SafeHouse.getSafehouseList().isEmpty()) {
            if (!warnedEmpty) {
                warnedEmpty = true;
                Debug.logGameplay("inSafehouse: no safehouses registered on server - safehouse item protection inactive");
            }
            return false;
        }
        dump();
        IsoGridSquare square = item.getSquare();
        boolean inside = square != null
            && (Config.safehouseProtectionMargin > 0
                ? near(square, Config.safehouseProtectionMargin)
                : SafeHouse.getSafeHouse(square) != null);
        if (inside) {
            if (insideLogs++ < DETAIL_LOG_LIMIT) {
                Debug.logGameplay("inSafehouse: TRUE for " + type(item) + " at " + describe(square)
                    + " (margin=" + Config.safehouseProtectionMargin + ")");
            }
        } else if (square != null && near(square, 40)) {
            report("NOT inside", item, square);
        }
        return inside;
    }

    public static void markForSave(IsoWorldInventoryObject item) {
        if (saveHookLogs++ < DETAIL_LOG_LIMIT) {
            Debug.logGameplay("save hook fired: markForSave(" + type(item) + " at "
                + describe(item == null ? null : item.getSquare()) + ")");
        }
        mark(item);
    }

    public static void mark(IsoWorldInventoryObject item) {
        if (item == null) {
            return;
        }
        if (item.ignoreRemoveSandbox) {
            if (skipAlreadyLogs++ < DETAIL_LOG_LIMIT) {
                Debug.logGameplay("mark: already flagged " + type(item) + " at " + describe(item.getSquare()));
            }
            return;
        }
        IsoGridSquare square = item.getSquare();
        if (square == null) {
            if (skipNullSquareLogs++ < DETAIL_LOG_LIMIT) {
                Debug.logGameplay("mark: skipped " + type(item) + " because square==null (created before placement)");
            }
            return;
        }
        if (inSafehouse(item)) {
            item.setIgnoreRemoveSandbox(true);
            if (protectedLogs++ < SAFEHOUSE_LOG_LIMIT) {
                Debug.logGameplay("PROTECTED safehouse item " + type(item) + " at " + describe(square));
            }
        } else if (near(square, 40)) {
            report("NOT inside", item, square);
        }
    }

    private static String type(IsoWorldInventoryObject item) {
        return item == null || item.getItem() == null ? "?" : item.getItem().getFullType();
    }

    private static String describe(IsoGridSquare square) {
        return square == null ? "square=null" : square.getX() + "," + square.getY() + "," + square.getZ();
    }

    private static void report(String result, IsoWorldInventoryObject item, IsoGridSquare square) {
        if (nearLogs++ > NEAR_LOG_LIMIT) {
            return;
        }
        Debug.logGameplay("world item " + type(item) + " at " + describe(square) + " -> " + result);
    }

    private static void dump() {
        if (dumped) {
            return;
        }
        dumped = true;
        Debug.logGameplay("safehouse hook active: " + SafeHouse.getSafehouseList().size() + " safehouse(s) registered");
        for (int i = 0; i < SafeHouse.getSafehouseList().size(); i++) {
            SafeHouse safe = SafeHouse.getSafehouseList().get(i);
            Debug.logGameplay("safehouse " + safe.getTitle() + " bounds "
                + safe.getX() + "," + safe.getY() + " to " + safe.getX2() + "," + safe.getY2());
        }
    }

    private static boolean near(IsoGridSquare square, int margin) {
        for (int i = 0; i < SafeHouse.getSafehouseList().size(); i++) {
            SafeHouse safe = SafeHouse.getSafehouseList().get(i);
            if (square.getX() >= safe.getX() - margin && square.getX() < safe.getX2() + margin
                && square.getY() >= safe.getY() - margin && square.getY() < safe.getY2() + margin) {
                return true;
            }
        }
        return false;
    }
}
