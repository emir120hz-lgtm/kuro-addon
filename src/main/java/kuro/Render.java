package kuro;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

public class Render {
    static final MinecraftClient mc = MinecraftClient.getInstance();
    static final double MAX_DIST = 256;

    public static void render(WorldRenderContext ctx) {
        if (!(Kuro.on[3] || Kuro.on[4] || Kuro.on[5])) return;
        if (mc.world == null || mc.player == null) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;

        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(false);

        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ms.peek().getPositionMatrix();

        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(2f);

        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        if (Kuro.on[3]) drawHits(b, m, Mods.storageHits.values(), cam);
        if (Kuro.on[4]) drawHits(b, m, Mods.blockHits.values(), cam);

        if (Kuro.on[5]) {
            Vec3d look = Vec3d.fromPolar(ctx.camera().getPitch(), ctx.camera().getYaw());
            Vec3d start = cam.add(look);
            for (AbstractClientPlayerEntity e : mc.world.getPlayers()) {
                if (e == mc.player) continue;
                Vec3d off = e.getLerpedPos(td).subtract(e.getPos());
                Box bb = e.getBoundingBox().offset(off);
                box(b, m, bb, 1f, 0.2f, 0.2f, 1f);
                Vec3d c = bb.getCenter();
                line(b, m, start.x, start.y, start.z, c.x, c.y, c.z, 1f, 0.2f, 0.2f, 0.7f);
            }
        }

        BuiltBuffer built = b.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        ms.pop();
    }

    static void drawHits(BufferBuilder b, Matrix4f m, Iterable<List<Mods.Hit>> all, Vec3d cam) {
        for (List<Mods.Hit> l : all) {
            for (Mods.Hit h : l) {
                BlockPos p = h.pos();
                double dx = p.getX() + 0.5 - cam.x, dy = p.getY() + 0.5 - cam.y, dz = p.getZ() + 0.5 - cam.z;
                if (dx * dx + dy * dy + dz * dz > MAX_DIST * MAX_DIST) continue;
                int c = h.color();
                box(b, m, new Box(p), ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, 1f);
            }
        }
    }

    static void box(BufferBuilder b, Matrix4f m, Box bx, float r, float g, float bl, float a) {
        double x1 = bx.minX, y1 = bx.minY, z1 = bx.minZ, x2 = bx.maxX, y2 = bx.maxY, z2 = bx.maxZ;
        // alt
        line(b, m, x1, y1, z1, x2, y1, z1, r, g, bl, a);
        line(b, m, x2, y1, z1, x2, y1, z2, r, g, bl, a);
        line(b, m, x2, y1, z2, x1, y1, z2, r, g, bl, a);
        line(b, m, x1, y1, z2, x1, y1, z1, r, g, bl, a);
        // ust
        line(b, m, x1, y2, z1, x2, y2, z1, r, g, bl, a);
        line(b, m, x2, y2, z1, x2, y2, z2, r, g, bl, a);
        line(b, m, x2, y2, z2, x1, y2, z2, r, g, bl, a);
        line(b, m, x1, y2, z2, x1, y2, z1, r, g, bl, a);
        // dikeyler
        line(b, m, x1, y1, z1, x1, y2, z1, r, g, bl, a);
        line(b, m, x2, y1, z1, x2, y2, z1, r, g, bl, a);
        line(b, m, x2, y1, z2, x2, y2, z2, r, g, bl, a);
        line(b, m, x1, y1, z2, x1, y2, z2, r, g, bl, a);
    }

    static void line(BufferBuilder b, Matrix4f m, double x1, double y1, double z1,
                     double x2, double y2, double z2, float r, float g, float bl, float a) {
        b.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, bl, a);
        b.vertex(m, (float) x2, (float) y2, (float) z2).color(r, g, bl, a);
    }
}
