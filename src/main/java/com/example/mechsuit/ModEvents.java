package com.example.mechsuit;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class ModEvents {
    @Mod.EventBusSubscriber(modid = "mechsuit", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            e.register(Keys.MENU);
            e.register(Keys.PUNCH);
            e.register(Keys.FIRE);
            e.register(Keys.ROCKET);
            e.register(Keys.DASH);
        }
    }

    @Mod.EventBusSubscriber(modid = "mechsuit", value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent ev) {
            if (ev.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                Mech.reset();
                Net.clear();
                return;
            }
            Mech.tick(mc);
            Net.tick();
            while (Keys.MENU.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new RadialMenuScreen());
            }
            while (Keys.PUNCH.consumeClick()) Mech.punch();
            while (Keys.ROCKET.consumeClick()) Mech.rockets();
            while (Keys.DASH.consumeClick()) Mech.dash();
        }
    }
}
