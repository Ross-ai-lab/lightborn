package lightborn.test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lightborn.ChestWardClientTest;
import lightborn.CreativeSearchClientTest;
import lightborn.EconomyTest;
import lightborn.ExpansionClientTest;
import lightborn.FlightClientTest;
import lightborn.HomeClientTest;
import lightborn.LightUpdateClientTest;
import lightborn.OwnerTuningClientTest;
import lightborn.PhaseSixClientTest;
import lightborn.SerpentQuestClientTest;
import lightborn.StoryClientTest;
import lightborn.WardDragonShowcaseClientTest;
import lightborn.WeeklyShopDragonClientTest;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * The one client-test entry point (README.md, Testing rules). Runs every client test in one game launch; a test that
 * throws is recorded and the next one still runs, and failed {@link Soft} checks are gathered, so a run lists every
 * failure at once. Each test logs its verdict and time. The environment variable {@code LIGHTBORN_CLIENT_TESTS} (comma
 * separated simple names, set by {@code tools/build.ps1 -Only}) runs a subset.
 */
public final class Suite implements FabricClientGameTest {
    /** Every client test, in run order. A test file not listed here is deleted, never left to go stale. */
    static final List<FabricClientGameTest> TESTS = List.of(
            new VisualTest(),
            new WardDragonShowcaseClientTest(),
            new CreativeSearchClientTest(),
            new EconomyTest(),
            new PhaseSixClientTest(),
            new OwnerTuningClientTest(),
            new LightUpdateClientTest(),
            new SerpentQuestClientTest(),
            new WeeklyShopDragonClientTest(),
            new ExpansionClientTest(),
            new StoryClientTest(),
            new HomeClientTest(),
            new ChestWardClientTest(),
            new FlightClientTest());

    @Override
    public void runTest(ClientGameTestContext context) {
        String only = System.getenv().getOrDefault("LIGHTBORN_CLIENT_TESTS", "");
        Set<String> wanted = Arrays.stream(only.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        Soft.startHeartbeat();
        var known = TESTS.stream().map(t -> t.getClass().getSimpleName()).collect(Collectors.toSet());
        for (String name : wanted)
            Soft.require(known.contains(name), "no client test named " + name + "; the tests are " + known);
        for (var test : TESTS) {
            String name = test.getClass().getSimpleName();
            if (!wanted.isEmpty() && !wanted.contains(name)) continue;
            Soft.begin(name);
            int before = Soft.count();
            long start = System.nanoTime();
            try {
                test.runTest(context);
            } catch (Throwable e) {
                Soft.stopped(name, e);
            }
            Soft.LOG.info(
                    "LIGHTBORN CLIENT TEST {} {} in {} s",
                    name,
                    Soft.count() == before ? "PASS" : "FAIL",
                    (System.nanoTime() - start) / 1_000_000_000L);
        }
        var failed = Soft.failures();
        if (!failed.isEmpty())
            throw new AssertionError(failed.size() + " client check(s) failed:\n  " + String.join("\n  ", failed));
    }
}
