package com.example.mechsuit;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class Keys {
    private static KeyMapping k(String n, int key) { return new KeyMapping("key.mechsuit." + n, key, "key.categories.mechsuit"); }
    public static final KeyMapping MENU   = k("menu",   InputConstants.KEY_G);
    public static final KeyMapping PUNCH  = k("punch",  InputConstants.KEY_R);
    public static final KeyMapping FIRE   = k("fire",   InputConstants.KEY_Z);
    public static final KeyMapping ROCKET = k("rocket", InputConstants.KEY_X);
    public static final KeyMapping DASH   = k("dash",   InputConstants.KEY_C);
}
