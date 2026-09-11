package com.puxadinho;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import zombie.ZomboidFileSystem;

/**
 * Logging for the agent, gated by {@code DebugLogging} in {@code
 * Puxadinho.ini}.
 *
 * <p>When the flag is off, every log is silent. When on, the one-time startup
 * fingerprint (build + jar path/hash) is emitted from {@link
 * Config#load()} so the loaded jar can be identified, followed by normal
 * diagnostics. {@link #error} always prints to {@code System.err} so failures
 * are never hidden, and is mirrored to the log files while debug logging is on.
 *
 * <p>Lines are also appended to {@code Puxadinho-debug.log} next to the
 * loaded agent jar, and, once the game is ready, to
 * {@code %USERPROFILE%\Zomboid\Puxadinho-debug.log}. This does not depend on
 * Project Zomboid's own log rotation, which stops writing after startup.
 */
public final class Debug {
    public static final String BUILD = "1.0.0+safehouse-field-fix";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String FILE_NAME = "Puxadinho-debug.log";

    /** Off until Config applies the ini value; no output at all when off. */
    private static volatile boolean enabled;
    private static volatile boolean startupLogged;

    private Debug() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Gated informational log. */
    public static void log(String message) {
        write(message, false, false);
    }

    /**
     * Gated gameplay log, also mirrored to the Zomboid cache directory. Only
     * call once the game is initialised ({@link ZomboidFileSystem} ready).
     */
    public static void logGameplay(String message) {
        write(message, true, false);
    }

    /** Always prints to stderr, even when debug logging is off. */
    public static void error(String message) {
        write(message, true, true);
    }

    private static synchronized void write(String message, boolean includeCacheDir, boolean error) {
        String line = "[" + LocalDateTime.now().format(FMT) + "] [Puxadinho] " + message;
        if (error) {
            System.err.println(line);
        } else if (enabled) {
            System.out.println(line);
        }
        if (!enabled) {
            return;
        }
        for (File target : targets(includeCacheDir)) {
            try (FileWriter writer = new FileWriter(target, true)) {
                writer.write(line + System.lineSeparator());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Emits the build/jar fingerprint once, after the config has enabled
     * logging. Emitted at most once per JVM.
     */
    public static synchronized void logStartupOnce() {
        if (startupLogged || !enabled) {
            return;
        }
        startupLogged = true;
        emitStartupFingerprint();
    }

    private static void emitStartupFingerprint() {
        log("=== Puxadinho build " + BUILD + " ===");
        log("agent code source: " + codeSource());
        log("agent jar fingerprint: " + fingerprint());
        log("jvm: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        log("os: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        log("working dir: " + System.getProperty("user.dir"));
        log("log file next to jar: " + new File(jarDirOrDot(), FILE_NAME));
        log("log mirrored to cache dir once ready: "
            + new File(System.getProperty("user.home"), "Zomboid" + File.separator + FILE_NAME));
    }

    public static String build() {
        return BUILD;
    }

    private static List<File> targets(boolean includeCacheDir) {
        List<File> targets = new ArrayList<>(2);
        File jarDir = jarDir();
        if (jarDir != null) {
            targets.add(new File(jarDir, FILE_NAME));
        }
        if (includeCacheDir) {
            File cache = cacheDir();
            if (cache != null && (jarDir == null || !cache.equals(jarDir))) {
                targets.add(new File(cache, FILE_NAME));
            }
        }
        return targets;
    }

    private static File codeSourceFile() {
        try {
            return new File(Debug.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String codeSource() {
        File file = codeSourceFile();
        return file == null ? "unavailable" : file.toString();
    }

    private static File jarDir() {
        File file = codeSourceFile();
        if (file == null) {
            return null;
        }
        return file.isDirectory() ? file : file.getParentFile();
    }

    private static File jarDirOrDot() {
        File dir = jarDir();
        return dir == null ? new File(".") : dir;
    }

    private static String fingerprint() {
        File file = codeSourceFile();
        if (file == null) {
            return "unavailable";
        }
        if (!file.isFile()) {
            return "not a file: " + file;
        }
        return file.getName()
            + " size=" + file.length()
            + " mtime=" + LocalDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault())
            + " sha256=" + sha256(file);
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private static File cacheDir() {
        try {
            if (ZomboidFileSystem.instance != null) {
                String dir = ZomboidFileSystem.instance.getCacheDir();
                if (dir != null) {
                    return new File(dir);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
