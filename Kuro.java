package kuro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Kuro implements ClientModInitializer {
    static final MinecraftClient mc = MinecraftClient.getInstance();

    // 0 Fly, 1 AutoTotem, 2 AutoCrystal, 3 StorageESP, 4 BlockESP, 5 PlayerESP  (6 = BaseList, aksiyon)
    static final boolean[] on = new boolean[6];
    static final String[] NAMES = {"Fly", "AutoTotem", "AutoCrystal", "StorageESP", "BlockESP", "PlayerESP", "BaseList"};
    // menuden degistirilebilir tus atamalari (GLFW key code, -1 = atanmamis)
    static final int[] binds = {
            GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_C,
            GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_H
    };
    static final boolean[] prev = new boolean[7];
    static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        loadBinds();
        menuKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.kuro.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "Kuro"));

        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (c.player == null || c.world == null) return;

            while (menuKey.wasPressed()) {
                if (c.currentScreen == null) c.setScreen(new KuroScreen());
            }

            long win = c.getWindow().getHandle();
            for (int i = 0; i < binds.length; i++) {
                boolean down = binds[i] >= 0 && InputUtil.isKeyPressed(win, binds[i]);
                if (down && !prev[i] && c.currentScreen == null) toggle(i);
                prev[i] = down;
            }

            if (on[0]) Mods.fly();
            if (on[1]) Mods.totem();
            if (on[2]) Mods.crystal();
            if (on[3] || on[4]) Mods.scanTick();
        });

        WorldRenderEvents.LAST.register(Render::render);

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            int y = 4;
            for (int i = 0; i < on.length; i++) {
                if (!on[i]) continue;
                ctx.drawTextWithShadow(mc.textRenderer, NAMES[i], 4, y, 0x55FF55);
                y += 10;
            }
        });
    }

    static void toggle(int i) {
        if (i == 6) {
            Mods.printBases();
            return;
        }
        on[i] = !on[i];
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("[Kuro] " + NAMES[i] + (on[i] ? " ACIK" : " KAPALI")), true);
    }

    // ---- tus atamalarini kaydet / yukle (oyun klasoru/kuro_keys.txt) ----
    static Path keysFile() {
        return FabricLoader.getInstance().getGameDir().resolve("kuro_keys.txt");
    }

    static void saveBinds() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < binds.length; i++) sb.append(NAMES[i]).append('=').append(binds[i]).append('\n');
        try {
            Files.writeString(keysFile(), sb.toString());
        } catch (IOException ignored) {
        }
    }

    static void loadBinds() {
        Path f = keysFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] p = line.trim().split("=");
                if (p.length != 2) continue;
                for (int i = 0; i < NAMES.length; i++) {
                    if (NAMES[i].equals(p[0].trim())) binds[i] = Integer.parseInt(p[1].trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
    }
}
