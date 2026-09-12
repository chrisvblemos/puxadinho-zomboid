package com.puxadinho.patches.death;

import com.puxadinho.Config;
import com.puxadinho.Debug;

import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.network.chat.ChatServer;

/**
 * Builds and sends the configurable player-death announcements. The cause of
 * death is classified into a stable key ({@code player}, {@code zombie}, ...)
 * which selects a message template from {@code Puxadinho.ini}.
 */
public final class DeathMessageGuard {
    private static final long HOURS_PER_DAY = 24L;
    private static final long DAYS_PER_MONTH = 30L;

    private DeathMessageGuard() {
    }

    public static void killed(IsoPlayer victim, IsoGameCharacter killer, HandWeapon weapon) {
        Config.load();
        if (!GameServer.server || !Config.deathMessagesEnabled || victim == null || victim.isAnimal()) {
            return;
        }
        try {
            String cause = cause(victim, killer);
            String character = name(victim);
            String username = username(victim);
            String weaponName = weapon == null || weapon.getName() == null ? "" : weapon.getName();
            String message = apply(template(cause), character, username, killerName(killer, cause),
                weaponName, survived(victim.getHoursSurvived()), cause);
            ChatServer.getInstance().sendMessageToServerChat(message);
            Debug.log("death message (" + cause + "): " + message);
        } catch (Throwable t) {
            Debug.error("death message failed: " + t);
        }
    }

    private static String cause(IsoPlayer victim, IsoGameCharacter killer) {
        if (killer != null && killer.isAnimal()) {
            return "animal";
        }
        if (killer instanceof IsoPlayer) {
            return "player";
        }
        if (killer instanceof IsoZombie) {
            return "zombie";
        }
        if (victim.isOnFire()) {
            return "fire";
        }
        if (victim.isKilledByFall()) {
            return "fall";
        }
        return illness(victim);
    }

    private static String illness(IsoPlayer victim) {
        Stats stats = victim.getStats();
        if (victim.getBodyDamage().isInfected() || stats.get(CharacterStat.ZOMBIE_INFECTION) >= 100.0F) {
            return "infection";
        }
        if (victim.getBodyDamage().getGeneralWoundInfectionLevel() > 50.0F) {
            return "wound";
        }
        if (stats.get(CharacterStat.FOOD_SICKNESS) > 50.0F) {
            return "food";
        }
        if (stats.get(CharacterStat.POISON) > 50.0F) {
            return "poison";
        }
        if (stats.get(CharacterStat.THIRST) > 0.9F) {
            return "thirst";
        }
        if (stats.get(CharacterStat.HUNGER) > 0.9F) {
            return "hunger";
        }
        if (stats.get(CharacterStat.SICKNESS) > 0.9F) {
            return "sickness";
        }
        return "environment";
    }

    private static String template(String cause) {
        switch (cause) {
            case "player":
                return Config.deathMessagePlayer;
            case "zombie":
                return Config.deathMessageZombie;
            case "animal":
                return Config.deathMessageAnimal;
            case "fire":
                return Config.deathMessageFire;
            case "fall":
                return Config.deathMessageFall;
            case "infection":
                return Config.deathMessageInfection;
            case "wound":
                return Config.deathMessageWound;
            case "food":
                return Config.deathMessageFood;
            case "poison":
                return Config.deathMessagePoison;
            case "thirst":
                return Config.deathMessageThirst;
            case "hunger":
                return Config.deathMessageHunger;
            case "sickness":
                return Config.deathMessageSickness;
            default:
                return Config.deathMessageEnvironment;
        }
    }

    private static String apply(String template, String name, String username, String killer,
        String weapon, String survived, String cause) {
        String player = username == null || username.isEmpty() ? name : name + " (" + username + ")";
        String weaponSuffix = weapon == null || weapon.isEmpty() ? "" : " (" + weapon + ")";
        return template
            .replace("{weapon_suffix}", weaponSuffix)
            .replace("{player}", player)
            .replace("{username}", username == null ? "" : username)
            .replace("{name}", name)
            .replace("{killer}", killer == null ? "" : killer)
            .replace("{weapon}", weapon == null ? "" : weapon)
            .replace("{survived}", survived)
            .replace("{cause}", cause);
    }

    private static String killerName(IsoGameCharacter killer, String cause) {
        switch (cause) {
            case "player":
                return name((IsoPlayer)killer);
            case "zombie":
                return "a zombie";
            case "animal":
                return "an animal";
            case "fire":
                return "fire";
            case "fall":
                return "a fall";
            default:
                return "";
        }
    }

    private static String username(IsoPlayer player) {
        UdpConnection connection = GameServer.getConnectionFromPlayer(player);
        String username = connection != null ? connection.getUserName() : null;
        if (username == null || username.isEmpty()) {
            username = player.getUsername();
        }
        return username == null ? "" : username;
    }

    private static String name(IsoPlayer player) {
        return player.getDescriptor().getForename() + " " + player.getDescriptor().getSurname();
    }

    private static String survived(double hours) {
        long total = (long) Math.floor(Math.max(0.0, hours));
        long months = total / (DAYS_PER_MONTH * HOURS_PER_DAY);
        long days = (total % (DAYS_PER_MONTH * HOURS_PER_DAY)) / HOURS_PER_DAY;
        long remaining = total % HOURS_PER_DAY;
        StringBuilder sb = new StringBuilder();
        append(sb, months, "month");
        append(sb, days, "day");
        append(sb, remaining, "hour");
        return sb.length() == 0 ? "less than an hour" : sb.toString();
    }

    private static void append(StringBuilder sb, long value, String unit) {
        if (value <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value).append(' ').append(unit);
        if (value != 1) {
            sb.append('s');
        }
    }
}