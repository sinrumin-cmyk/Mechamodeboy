package com.example.mechsuit;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Shield {
    public enum Shape {
        ARC("Дуга"), DOME("Купол"), SPHERE("Сфера"), WALL("Стена");
        public final String label;
        Shape(String l) { label = l; }
    }

    public static Shape shape = Shape.ARC;
    public static int radius = 4;
    private static String lastBlock = "minecraft:glass";

    public static void nextShape() { shape = Shape.values()[(shape.ordinal() + 1) % Shape.values().length]; }
    public static void nextRadius() { radius = radius >= 6 ? 3 : radius + 1; }

    private static List<BlockPos> compute(Player p) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos c = p.blockPosition().above();
        int r = radius;

        if (shape == Shape.WALL) {
            Direction d = p.getDirection();
            int sx = d.getStepX(), sz = d.getStepZ();
            for (int w = -r; w <= r; w++)
                for (int h = -1; h <= r; h++)
                    out.add(new BlockPos(c.getX() + sx * r + (sz != 0 ? w : 0), c.getY() + h,
                                         c.getZ() + sz * r + (sx != 0 ? w : 0)));
            return out;
        }

        Vec3 look = p.getLookAngle();
        double hl = Math.sqrt(look.x * look.x + look.z * look.z);
        double fx = hl > 1e-4 ? look.x / hl : 0, fz = hl > 1e-4 ? look.z / hl : 1;
        int R = r + 1;
        for (int dx = -R; dx <= R; dx++)
            for (int dy = -R; dy <= R; dy++)
                for (int dz = -R; dz <= R; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (Math.abs(d - r) > 0.5) continue;
                    switch (shape) {
                        case DOME -> { if (dy < -1) continue; }
                        case ARC  -> { if (dy < -1 || (dx * fx + dz * fz) / Math.max(d, 1e-6) < 0.5) continue; }
                        default -> {}
                    }
                    out.add(c.offset(dx, dy, dz));
                }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    public static void place() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.level == null) return;
        if (p.getMainHandItem().getItem() instanceof BlockItem bi)
            lastBlock = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
        for (BlockPos pos : compute(p)) {
            var st = mc.level.getBlockState(pos);
            if (st.isAir() || st.canBeReplaced())
                Net.cmdBulk("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + lastBlock);
        }
    }

    /** Ломает только блоки того же типа, которым ставили щит (чтобы не снести постройки). */
    public static void breakAll() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.level == null) return;
        Block target = BuiltInRegistries.BLOCK.get(new ResourceLocation(lastBlock));
        for (BlockPos pos : compute(p))
            if (mc.level.getBlockState(pos).is(target)) breakBlock(pos);
    }

    /** Ломание обычным пакетом креатива (без команд, чат не засоряется). Дальше ~6 блоков сервер игнорирует. */
    public static void breakBlock(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Net.bulk(() -> {
            if (mc.player == null || mc.gameMode == null || mc.level == null) return;
            var s = mc.level.getBlockState(pos);
            if (!s.isAir() && s.getDestroySpeed(mc.level, pos) >= 0)
                mc.gameMode.startDestroyBlock(pos, Direction.UP);
        });
    }
}
