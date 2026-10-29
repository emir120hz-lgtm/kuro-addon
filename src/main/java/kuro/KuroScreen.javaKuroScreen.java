package kuro;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class KuroScreen extends Screen {
    private static final int ROW_H = 24;
    private final ButtonWidget[] bindBtns = new ButtonWidget[Kuro.NAMES.length];
    private int listening = -1;

    public KuroScreen() {
        super(Text.literal("Kuro"));
    }

    @Override
    protected void init() {
        int n = Kuro.NAMES.length;
        int tw = 130, bw = 100, gap = 6;
        int x = width / 2 - (tw + gap + bw) / 2;
        int y = height / 2 - (n * ROW_H) / 2 + 10;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            ButtonWidget toggle = ButtonWidget.builder(toggleText(i), b -> {
                Kuro.toggle(idx);
                b.setMessage(toggleText(idx));
            }).dimensions(x, y + i * ROW_H, tw, 20).build();

            bindBtns[i] = ButtonWidget.builder(bindText(i), b -> {
                listening = idx;
                refresh();
            }).dimensions(x + tw + gap, y + i * ROW_H, bw, 20).build();

            addDrawableChild(toggle);
            addDrawableChild(bindBtns[i]);
        }
    }

    private Text toggleText(int i) {
        if (i == 6) return Text.literal("Base listesini yazdir").formatted(Formatting.AQUA);
        boolean on = Kuro.on[i];
        return Text.literal(Kuro.NAMES[i] + ": " + (on ? "ON" : "OFF"))
                .formatted(on ? Formatting.GREEN : Formatting.RED);
    }

    private Text bindText(int i) {
        if (listening == i) return Text.literal("> tusa bas <").formatted(Formatting.YELLOW);
        int code = Kuro.binds[i];
        if (code < 0) return Text.literal("[YOK]").formatted(Formatting.GRAY);
        return Text.literal("[" + InputUtil.Type.KEYSYM.createFromCode(code).getLocalizedText().getString() + "]");
    }

    private void refresh() {
        for (int i = 0; i < bindBtns.length; i++) {
            if (bindBtns[i] != null) bindBtns[i].setMessage(bindText(i));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening >= 0) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listening = -1;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
                Kuro.binds[listening] = -1;
                Kuro.saveBinds();
                listening = -1;
            } else {
                Kuro.binds[listening] = keyCode;
                Kuro.saveBinds();
                listening = -1;
            }
            refresh();
            return true;
        }
        if (Kuro.menuKey != null && Kuro.menuKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int top = height / 2 - (Kuro.NAMES.length * ROW_H) / 2 - 14;
        ctx.drawCenteredTextWithShadow(textRenderer, "Kuro", width / 2, top, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "Sagdaki butona tikla, sonra tus ata (Esc: iptal, Backspace: temizle)",
                width / 2, height - 24, 0xAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
