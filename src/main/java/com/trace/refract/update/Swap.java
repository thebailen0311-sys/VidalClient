package com.trace.refract.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Tiny helper the updater starts just before the game exits. It waits for the game process to disappear, then puts
 * the downloaded jar in place of the old one.
 *
 * <p>It exists because Windows refuses to rename or delete a jar while the game has it open, and Fabric aborts when
 * two jars with the same mod id sit in {@code mods/}. Running from an extracted copy of this one class (not from the
 * mod jar) means nothing keeps the old jar locked while the swap happens. No launcher, no reboot, no manual step.
 *
 * <p>Args: old jar, staged new jar, game pid, final jar path.
 */
public final class Swap {
    private Swap() {}

    public static void main(String[] args) {
        if (args.length < 4) return;
        Path oldJar = Path.of(args[0]);
        Path staged = Path.of(args[1]);
        Path finalJar = Path.of(args[3]);
        long pid;
        try { pid = Long.parseLong(args[2]); } catch (Exception e) { return; }

        // One hour of half-second polls: long enough for any session, cheap enough to ignore.
        for (int i = 0; i < 7200; i++) {
            if (!alive(pid)) break;
            sleep(500);
        }
        sleep(1500); // let the JVM release its jar handles

        for (int i = 0; i < 120; i++) {
            try {
                Files.deleteIfExists(oldJar);
                Files.move(staged, finalJar, StandardCopyOption.REPLACE_EXISTING);
                cleanup(staged.getParent());
                return;
            } catch (Exception e) {
                sleep(500);
            }
        }
        // Could not swap (permissions, read-only folder): leave the staged jar and a note beside it.
        try {
            Files.writeString(staged.resolveSibling("SWAP-FAILED.txt"),
                    "Could not replace " + oldJar + "\nStaged jar: " + staged + "\n");
        } catch (Exception ignored) { }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void cleanup(Path dir) {
        if (dir == null) return;
        try {
            Files.deleteIfExists(dir.resolve("Swap.class"));
            Files.deleteIfExists(dir.resolve("swap.log"));
            Files.deleteIfExists(dir);
        } catch (Exception ignored) { }
    }
}
