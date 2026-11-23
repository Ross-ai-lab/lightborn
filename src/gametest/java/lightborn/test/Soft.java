package lightborn.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-test checks that record a failure and carry on, so one run reports every broken check instead of the first
 * (README.md, Testing rules). {@link Suite} reads the list at the end. Also keeps the heartbeat file that
 * {@code tools/build.ps1} watches: the client only ticks while the test thread lets it, so a stuck test or a hung
 * game both stop the heartbeat, and the watchdog names the last step it saw.
 */
public final class Soft {
    private Soft() {}

    static final Logger LOG = LoggerFactory.getLogger("lightborn-client-test");
    private static final List<String> FAILED = new ArrayList<>();
    private static volatile String test = "";
    private static volatile String step = "";
    private static int ticks;

    /** Starts the heartbeat: every second of client ticks rewrites {@code heartbeat.txt} with the current step. */
    static void startHeartbeat() {
        Path file = FabricLoader.getInstance().getGameDir().resolve("heartbeat.txt");
        beat(file);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 == 0) beat(file);
        });
    }

    private static void beat(Path file) {
        try {
            Files.writeString(file, test + (step.isEmpty() ? "" : "/" + step));
        } catch (Exception ignored) {
            // A missed beat only shortens the watchdog's patience; never fail a test over it.
        }
    }

    static void begin(String name) {
        test = name;
        step = "";
    }

    /** Names the step now running, for the heartbeat and the log. */
    public static void step(String name) {
        step = name;
        LOG.info("LIGHTBORN CLIENT SHOT {}/{}", test, name);
    }

    /** Records a failed check under the running test, and logs it at once. */
    public static void require(boolean ok, String why) {
        if (ok) return;
        FAILED.add(test + (step.isEmpty() ? "" : "/" + step) + ": " + why);
        LOG.error("LIGHTBORN CLIENT CHECK FAIL {}: {}", test, why);
    }

    static void stopped(String name, Throwable e) {
        FAILED.add(name + (step.isEmpty() ? "" : "/" + step) + " stopped: " + e);
        LOG.error("LIGHTBORN CLIENT TEST STOPPED " + name, e);
    }

    static List<String> failures() {
        return List.copyOf(FAILED);
    }

    static int count() {
        return FAILED.size();
    }
}
