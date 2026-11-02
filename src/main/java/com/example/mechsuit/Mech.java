package com.example.mechsuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Мех в стиле Titanfall: ~200 block_display из бетона (модель из mech.json) + невидимые шалкеры-коллизия.
 * Ты сидишь в кабине (груди меха): мод держит тебя в креативном полёте на нужной высоте над землёй.
 * Все команды короче 256 символов (лимит Minecraft).
 */
public final class Mech {
    public static boolean active = false;
    public static boolean punchBreaks = false;
    public static boolean hitPlayers = false;

    /** Если ноги/руки качаются в обратную сторону, поменяй на -1f */
    private static final float PITCH_SIGN = 1f;

    private record Grp(String id, double px, double py, double pz, double rest, List<float[]> boxes) {}

    private static List<Grp> groups;
    private static String[] blocks;
    private static double eyeY = 5.16;

    // шалкеры-ноги: left, y, fwd (влево +, вперёд +) относительно кабины
    private static final double[][] COL = {
        {0.78, 0, 0.07}, {0.78, 1, 0.07}, {0.78, 2, 0.07},
        {-0.78, 0, 0.07}, {-0.78, 1, 0.07}, {-0.78, 2, 0.07}
    };

    private static String name = "";
    private static boolean ready;
    private static double baseY, lx, lz;
    private static float upperYaw, lowerYaw, phase, amp, aimBlend;
    private static int punchT = -1, aimTicks, fireCd, rocketsLeft, rocketDelay, dashCd, rocketCd;
    private static final Map<String, String> last = new HashMap<>();
    private static final Random rnd = new Random();

    // ---------- загрузка модели ----------
    private static void load() {
        if (groups != null) return;
        groups = new ArrayList<>();
        try (var r = new InputStreamReader(Mech.class.getResourceAsStream("/mech.json"), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray bl = o.getAsJsonArray("blocks");
            blocks = new String[bl.size()];
            for (int i = 0; i < blocks.length; i++) blocks[i] = bl.get(i).getAsString();
            eyeY = o.getAsJsonArray("eye").get(1).getAsDouble();
            for (var e : o.getAsJsonObject("groups").entrySet()) {
                JsonObject g = e.getValue().getAsJsonObject();
                JsonArray pv = g.getAsJsonArray("pivot");
                List<float[]> boxes = new ArrayList<>();
                for (var be : g.getAsJsonArray("boxes")) {
                    JsonArray a = be.getAsJsonArray();
                    float[] f = new float[a.size()];
                    for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
                    boxes.add(f);
                }
                groups.add(new Grp(e.getKey(), pv.get(0).getAsDouble(), pv.get(1).getAsDouble(), pv.get(2).getAsDouble(),
                    g.has("restAngle") ? g.get("restAngle").getAsDouble() : 0, boxes));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static String num(double v) {
        String s = new BigDecimal(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return (s.equals("-0") ? "0" : s);
    }
    private static String f1(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    private static String grpTag() { return "m_" + name; }
    private static String tag(String id) { return "m_" + name + "_" + id; }

    private static String boxCmd(Grp g, float[] b) {
        StringBuilder sb = new StringBuilder("summon block_display ~ ~ ~ {Tags:[\"")
            .append(grpTag()).append("\",\"").append(tag(g.id())).append("\"],block_state:{Name:\"")
            .append(blocks[(int) b[0]]).append("\"},transformation:{");
        if (Math.abs(b[7]) > 5e-4 || Math.abs(b[8]) > 5e-4 || Math.abs(b[9]) > 5e-4)
            sb.append("left_rotation:[").append(num(b[7])).append("f,").append(num(b[8])).append("f,")
              .append(num(b[9])).append("f,").append(num(b[10])).append("f],");
        sb.append("translation:[").append(num(b[1])).append("f,").append(num(b[2])).append("f,").append(num(b[3])).append("f],")
          .append("scale:[").append(num(b[4])).append("f,").append(num(b[5])).append("f,").append(num(b[6])).append("f]}}");
        return sb.toString();
    }

    private static String shulkerCmd(int i) {
        return "summon shulker ~ ~ ~ {Tags:[\"" + grpTag() + "\",\"" + tag("c" + i) + "\"],"
            + "NoAI:1b,Silent:1b,Invulnerable:1b,NoGravity:1b,PersistenceRequired:1b,DeathLootTable:\"empty\","
            + "ActiveEffects:[{Id:14,Amplifier:0,Duration:1000000,ShowParticles:0b}]}";
    }

    // ---------- вкл/выкл ----------
    public static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;
        if (active) {
            Net.cmd("execute as @e[tag=" + grpTag() + "] run kill @s");
            Net.cmd("effect clear @s minecraft:invisibility");
            reset();
            return;
        }
        if (!p.getAbilities().mayfly) {
            p.displayClientMessage(Component.literal("Нужен креатив (полёт), чтобы сидеть в кабине"), true);
            return;
        }
        load();
        if (groups.isEmpty()) {
            p.displayClientMessage(Component.literal("mech.json не загрузился"), true);
            return;
        }
        name = p.getGameProfile().getName();
        Net.cmd("gamerule sendCommandFeedback false");
        Net.cmd("gamerule logAdminCommands false");
        Net.cmd("effect give @s minecraft:invisibility infinite 0 true");
        Net.cmd("execute as @e[tag=" + grpTag() + "] run kill @s");
        for (Grp g : groups) for (float[] b : g.boxes()) Net.cmdBulk(boxCmd(g, b));
        for (int i = 0; i < COL.length; i++) Net.cmdBulk(shulkerCmd(i));
        ready = false;
        Net.bulk(() -> ready = true);

        baseY = ground(mc, p, p.getY());
        upperYaw = lowerYaw = p.getYRot();
        lx = p.getX(); lz = p.getZ();
        phase = 0; amp = 0; aimBlend = 0; aimTicks = 0; punchT = -1;
        rocketsLeft = 0; dashCd = 0; rocketCd = 0;
        last.clear();
        active = true;
    }

    public static void reset() {
        active = false; ready = false; punchT = -1; rocketsLeft = 0;
        last.clear();
    }

    private static double ground(Minecraft mc, Player p, double fallback) {
        Vec3 s = new Vec3(p.getX(), p.getY() + 0.5, p.getZ());
        BlockHitResult r = mc.level.clip(new ClipContext(s, s.add(0, -48, 0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.MISS ? fallback : r.getLocation().y;
    }

    /** локальные (влево, вперёд) -> мировые смещения x,z */
    private static double[] rot(double left, double fwd, float yawDeg) {
        double r = Math.toRadians(yawDeg), s = Math.sin(r), c = Math.cos(r);
        return new double[] { left * c - fwd * s, left * s + fwd * c };
    }

    // ---------- способности ----------
    public static void punch() { if (active && punchT < 0) punchT = 0; }

    public static void shootOnce() {
        Minecraft mc = Minecraft.getInstance();
        if (active && mc.player != null) { aimTicks = 12; shoot(mc, mc.player); }
    }

    private static void shoot(Minecraft mc, Player p) {
        Vec3 from = p.getEyePosition(), look = p.getLookAngle(), to = from.add(look.scale(64));
        BlockHitResult bh = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 end = bh.getType() == HitResult.Type.MISS ? to : bh.getLocation();
        AABB box = p.getBoundingBox().expandTowards(look.scale(64)).inflate(1);
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(p, from, end, box,
            e -> !e.isSpectator() && e.isPickable() && e != p && !(e instanceof Shulker), 64 * 64);
        if (eh != null) {
            end = eh.getLocation();
            Net.cmd("damage " + eh.getEntity().getUUID() + " 5 player_attack by @s");
        }
        Net.cmd("particle crit " + f1(end.x) + " " + f1(end.y) + " " + f1(end.z) + " 0.2 0.2 0.2 0.3 8 force");
        // трассер (виден только тебе, дёшево)
        Vec3 muzzle = from.add(look.scale(2.0)).add(0, -0.6, 0);
        double len = end.distanceTo(muzzle);
        for (double d = 0; d < len; d += 1.5) {
            Vec3 q = muzzle.add(end.subtract(muzzle).normalize().scale(d));
            mc.level.addParticle(ParticleTypes.END_ROD, q.x, q.y, q.z, 0, 0, 0);
        }
        mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.7f, 1.8f, false);
    }

    public static void rockets() {
        if (!active || rocketCd > 0) return;
        rocketsLeft = 4; rocketDelay = 0; rocketCd = 100; aimTicks = 20;
    }

    private static void launchRocket(Player p) {
        Vec3 look = p.getLookAngle();
        Vec3 dir = look.add((rnd.nextDouble() - 0.5) * 0.08, (rnd.nextDouble() - 0.5) * 0.08, (rnd.nextDouble() - 0.5) * 0.08).normalize();
        double[] side = rot((rnd.nextDouble() - 0.5) * 2.4, 0, p.getYRot());
        Vec3 s = p.getEyePosition().add(look.scale(3.0)).add(side[0], -0.3, side[1]);
        Net.cmd("summon fireball " + num(s.x) + " " + num(s.y) + " " + num(s.z)
            + " {ExplosionPower:2b,power:[" + num(dir.x * 0.1) + "," + num(dir.y * 0.1) + "," + num(dir.z * 0.1)
            + "],Motion:[" + num(dir.x) + "," + num(dir.y) + "," + num(dir.z) + "]}");
        Net.cmd("playsound minecraft:entity.firework_rocket.launch player @a " + f1(s.x) + " " + f1(s.y) + " " + f1(s.z) + " 1 1");
    }

    public static void dash() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (!active || p == null || dashCd > 0) return;
        Vec3 l = p.getLookAngle();
        Vec3 h = new Vec3(l.x, 0, l.z).normalize();
        Vec3 m = p.getDeltaMovement();
        p.setDeltaMovement(h.x * 1.8, m.y, h.z * 1.8);
        dashCd = 30;
        mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1f, 0.6f, false);
    }

    private static void hit(Player p) {
        double[] f = rot(0, 3.2, upperYaw);
        String pos = f1(p.getX() + f[0]) + " " + f1(baseY + 2.0) + " " + f1(p.getZ() + f[1]);
        String who = hitPlayers ? "name=!" + name : "type=!player";
        Net.cmd("execute positioned " + pos + " as @e[distance=..3.5,type=!block_display,type=!shulker,"
            + "type=!item,type=!experience_orb," + who + "] run damage @s 10 player_attack by @a[name=" + name + ",limit=1]");
        if (punchBreaks) {
            BlockPos fc = BlockPos.containing(p.getX() + f[0], baseY + 2.0, p.getZ() + f[1]);
            for (BlockPos b : BlockPos.betweenClosed(fc.offset(-1, -1, -1), fc.offset(1, 1, 1))) {
                BlockPos q = b.immutable();
                if (q.distSqr(p.blockPosition()) <= 30) Shield.breakBlock(q);
            }
        }
    }

    // ---------- тик ----------
    public static void tick(Minecraft mc) {
        Player p = mc.player;
        if (!active || p == null || mc.level == null) return;

        // земля под мехом и высота кабины
        baseY += (ground(mc, p, baseY) - baseY) * 0.35;
        if (p.getAbilities().mayfly) {
            p.getAbilities().flying = true;
            double target = baseY + eyeY - p.getEyeHeight();
            Vec3 m = p.getDeltaMovement();
            p.setDeltaMovement(m.x, Mth.clamp((target - p.getY()) * 0.4, -1.0, 1.0), m.z);
        }

        // ходьба
        double dx = p.getX() - lx, dz = p.getZ() - lz;
        lx = p.getX(); lz = p.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        amp += (Mth.clamp((float) speed * 4f, 0f, 1f) - amp) * 0.3f;
        phase += (float) speed * 1.4f;

        // повороты: корпус (кабина) строго по взгляду, ноги и таз отстают
        upperYaw = p.getYRot();
        float diff = Mth.wrapDegrees(upperYaw - lowerYaw);
        if (speed > 0.02) lowerYaw += Mth.clamp(diff, -12f, 12f);
        else if (Math.abs(diff) > 45f) lowerYaw += Mth.clamp(diff - Math.signum(diff) * 45f, -12f, 12f);

        // способности
        if (dashCd > 0) dashCd--;
        if (rocketCd > 0) rocketCd--;
        if (fireCd > 0) fireCd--;
        if (mc.screen == null && Keys.FIRE.isDown()) {
            aimTicks = 12;
            if (fireCd == 0) { shoot(mc, p); fireCd = 3; }
        }
        if (rocketsLeft > 0 && --rocketDelay <= 0) { launchRocket(p); rocketsLeft--; rocketDelay = 3; }
        if (aimTicks > 0) { aimTicks--; aimBlend = Math.min(1f, aimBlend + 0.4f); }
        else aimBlend = Math.max(0f, aimBlend - 0.2f);

        float punchPitch = 0f;
        if (punchT >= 0) {
            punchT++;
            punchPitch = -80f * Mth.sin((float) Math.PI * punchT / 9f);
            if (punchT == 3) hit(p);
            if (punchT > 9) punchT = -1;
        }

        if (!ready) return;

        float leg = Mth.sin(phase) * 28f * amp;
        float arm = Mth.sin(phase) * 10f * amp;

        for (Grp g : groups) {
            String id = g.id();
            boolean upper = id.equals("torso") || id.startsWith("hand");
            float yaw = upper ? upperYaw : lowerYaw;
            float pitch = 0f;
            switch (id) {
                case "legL" -> pitch = leg;
                case "legR" -> pitch = -leg;
                case "handL", "handR" -> {
                    float aim = Mth.clamp(p.getXRot() - 90f + (float) g.rest(), -170f, 20f);
                    float walk = id.equals("handL") ? -arm : arm;
                    pitch = Mth.lerp(aimBlend, walk, aim);
                    if (id.equals("handR") && punchT >= 0) pitch = punchPitch;
                }
                default -> {}
            }
            double[] w = rot(g.px(), g.pz(), yaw);
            send(id, p.getX() + w[0], baseY + g.py(), p.getZ() + w[1], yaw, pitch * PITCH_SIGN, true);
        }
        for (int i = 0; i < COL.length; i++) {
            double[] w = rot(COL[i][0], COL[i][2], lowerYaw);
            send("c" + i, p.getX() + w[0], baseY + COL[i][1], p.getZ() + w[1], 0, 0, false);
        }
    }

    private static void send(String id, double x, double y, double z, float yaw, float pitch, boolean rotate) {
        String t = Net.f(x) + " " + Net.f(y) + " " + Net.f(z) + (rotate ? " " + Net.f(yaw) + " " + Net.f(pitch) : "");
        if (t.equals(last.get(id))) return;
        last.put(id, t);
        Net.cmd("execute as @e[tag=" + tag(id) + "] run tp @s " + t);
    }
}
