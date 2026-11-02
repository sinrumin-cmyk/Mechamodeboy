package com.example.mechsuit;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

public class RadialMenuScreen extends Screen {
    private record Item(Supplier<String> label, Runnable action) {}

    private final List<Item> items = List.of(
        new Item(() -> Mech.active ? "Мех: убрать" : "Мех: вызвать", Mech::toggle),
        new Item(() -> "Выстрел (Z)", Mech::shootOnce),
        new Item(() -> "Ракеты (X)", Mech::rockets),
        new Item(() -> "Рывок (C)", Mech::dash),
        new Item(() -> "Удар (R)", Mech::punch),
        new Item(() -> "Щит: поставить", Shield::place),
        new Item(() -> "Щит: сломать", Shield::breakAll),
        new Item(() -> "Удар ломает: " + (Mech.punchBreaks ? "да" : "нет"), () -> Mech.punchBreaks = !Mech.punchBreaks)
    );

    private int mx, my;

    public RadialMenuScreen() { super(Component.literal("Mech")); }

    @Override public boolean isPauseScreen() { return false; }

    private int hovered(int x, int y) {
        double dx = x - width / 2.0, dy = y - height / 2.0;
        if (Math.sqrt(dx * dx + dy * dy) < 18) return -1;
        double step = 2 * Math.PI / items.size();
        double a = Math.atan2(dy, dx) + Math.PI / 2;
        return Math.floorMod((int) Math.round(a / step), items.size());
    }

    private void choose(int i) {
        onClose();
        if (i >= 0) items.get(i).action().run();
    }

    @Override
    public void tick() {
        long win = minecraft.getWindow().getWindow();
        if (!InputConstants.isKeyDown(win, Keys.MENU.getKey().getValue())) choose(hovered(mx, my));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        choose(hovered((int) x, (int) y));
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        mx = mouseX; my = mouseY;
        g.fill(0, 0, width, height, 0x66000000);
        int cx = width / 2, cy = height / 2, hov = hovered(mouseX, mouseY);
        double step = 2 * Math.PI / items.size();
        for (int i = 0; i < items.size(); i++) {
            double a = i * step - Math.PI / 2;
            int x = cx + (int) Math.round(Math.cos(a) * 85), y = cy + (int) Math.round(Math.sin(a) * 85);
            g.fill(x - 56, y - 12, x + 56, y + 12, i == hov ? 0xDD3A7BD5 : 0xAA202020);
            g.renderOutline(x - 56, y - 12, 112, 24, i == hov ? 0xFFFFFFFF : 0x66FFFFFF);
            g.drawCenteredString(font, items.get(i).label().get(), x, y - 4, 0xFFFFFF);
        }
        g.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFFFFFFFF);
    }
}
