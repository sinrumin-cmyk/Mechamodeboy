package com.example.mechsuit;

import net.minecraft.client.Minecraft;
import java.util.ArrayDeque;
import java.util.Locale;

/** Очереди: prio (анимация меха, шлётся всё сразу) и bulk (щит, лимит на тик). */
public final class Net {
    private static final ArrayDeque<Runnable> prio = new ArrayDeque<>();
    private static final ArrayDeque<Runnable> bulk = new ArrayDeque<>();
    public static int BULK_PER_TICK = 30;

    public static void cmd(String c) { prio.add(() -> send(c)); }
    public static void cmdBulk(String c) { bulk.add(() -> send(c)); }
    public static void bulk(Runnable r) { bulk.add(r); }

    private static void send(String c) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.connection.sendCommand(c);
    }

    public static void tick() {
        Runnable r;
        while ((r = prio.poll()) != null) r.run();
        for (int i = 0; i < BULK_PER_TICK && (r = bulk.poll()) != null; i++) r.run();
    }

    public static void clear() { prio.clear(); bulk.clear(); }
    public static String f(double v) { return String.format(Locale.ROOT, "%.3f", v); }
}
