package lightborn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Runs delayed actions on the server thread (meteor impacts, boss telegraphs, test steps). */
public final class Scheduler {
    private Scheduler() {}

    private record Task(long at, Runnable action) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static long tick;

    public static long now() {
        return tick;
    }

    public static void later(int ticks, Runnable action) {
        TASKS.add(new Task(tick + Math.max(1, ticks), action));
    }

    /** Called once per server tick. */
    static void tick() {
        tick++;
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.at() <= tick) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) {
            try {
                t.action().run();
            } catch (RuntimeException e) {
                Lightborn.LOG.error("Scheduled task failed", e);
            }
        }
    }

    static void clear() {
        TASKS.clear();
    }
}
