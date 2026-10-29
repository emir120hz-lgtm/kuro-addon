package kuro;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Mods {
    static final MinecraftClient mc = MinecraftClient.getInstance();

    // ---- ayarlar (istedigin gibi degistir) ----
    static final double FLY_H = 0.8;          // yatay hiz (blok/tick)
    static final double FLY_V = 0.5;          // dikey hiz
    static final double MIN_TARGET_DMG = 5;   // crystal yerlestirmek icin min hedef hasari
    static final double MAX_SELF_DMG = 6;     // crystal yerlestirmek icin max kendi hasarin
    static final int MIN_BASE_SCORE = 5;      // base sayilmasi icin 3x3 chunk'ta min depo sayisi
    static final int MAX_HITS_PER_CHUNK = 2000;

    record Hit(BlockPos pos, int color) {}

    static final Map<Long, List<Hit>> storageHits = new HashMap<>();
    static final Map<Long, List<Hit>> blockHits = new HashMap<>();
    static final Set<Block> targets = new HashSet<>();
    static final Set<String> logged = new HashSet<>();
    static final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    static World lastWorld;
    static int timer = 0, flyTick = 0, crystalCd = 0, totemCd = 0;

    static {
        loadTargets();
    }

    static void msg(String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Kuro] " + s), false);
    }

    // =====================================================================
    // FLY
    // =====================================================================
    static void fly() {
        ClientPlayerEntity p = mc.player;
        double rad = Math.toRadians(p.getYaw());
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);
        double mx = 0, mz = 0, my = 0;
        var o = mc.options;
        if (o.forwardKey.isPressed()) { mx += fx; mz += fz; }
        if (o.backKey.isPressed()) { mx -= fx; mz -= fz; }
        if (o.rightKey.isPressed()) { mx += rx; mz += rz; }
        if (o.leftKey.isPressed()) { mx -= rx; mz -= rz; }
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len > 0) { mx = mx / len * FLY_H; mz = mz / len * FLY_H; }
        if (o.jumpKey.isPressed()) my += FLY_V;
        if (o.sneakKey.isPressed()) my -= FLY_V;
        // anti-kick: havada sabit durunca ara sira cok az asagi in
        if (my == 0 && ++flyTick % 30 == 0) my = -0.04;
        p.setVelocity(mx, my, mz);
    }

    // =====================================================================
    // AUTO TOTEM
    // =====================================================================
    static void totem() {
        ClientPlayerEntity p = mc.player;
        if (totemCd-- > 0) return;
        if (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        if (mc.currentScreen instanceof HandledScreen<?>) return;
        for (int i = 0; i < 36; i++) {
            if (!p.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) continue;
            int slot = i < 9 ? 36 + i : i; // hotbar 36-44, envanter 9-35
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
            totemCd = 2;
            return;
        }
    }

    // =====================================================================
    // AUTO CRYSTAL
    // =====================================================================
    static void crystal() {
        ClientPlayerEntity p = mc.player;
        if (crystalCd-- > 0) return;

        PlayerEntity target = null;
        double best = 12 * 12;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p || !o.isAlive() || o.isSpectator()) continue;
            double d = p.squaredDistanceTo(o);
            if (d < best) { best = d; target = o; }
        }
        if (target == null) return;

        float selfHp = p.getHealth() + p.getAbsorptionAmount();
        Vec3d eye = p.getEyePos();

        // 1) crystal patlat
        EndCrystalEntity bestC = null;
        float bestTd = 0;
        for (EndCrystalEntity c : mc.world.getEntitiesByClass(EndCrystalEntity.class, p.getBoundingBox().expand(5), e -> true)) {
            if (distToBox(eye, c.getBoundingBox()) > 3.0) continue;
            Vec3d cp = c.getPos();
            float td = dmg(cp, target), sd = dmg(cp, p);
            if (sd >= selfHp - 1) continue;           // kendini oldurme
            if (td < 3 && sd < 4) continue;           // ne hedefe ne bize anlamli hasar vermiyor
            if (td > bestTd) { bestTd = td; bestC = c; }
        }
        if (bestC != null) {
            mc.interactionManager.attackEntity(p, bestC);
            p.swingHand(Hand.MAIN_HAND);
            crystalCd = 1;
            return;
        }

        // 2) crystal yerlestir
        Hand hand = crystalHand(p);
        if (hand == null) return;
        BlockPos origin = p.getBlockPos();
        BlockPos bestPos = null;
        double bestScore = -999;
        for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 3; dy++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos pos = origin.add(dx, dy, dz);
            Vec3d center = Vec3d.ofCenter(pos);
            if (eye.squaredDistanceTo(center) > 4.5 * 4.5) continue;
            BlockState st = mc.world.getBlockState(pos);
            if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) continue;
            BlockPos up = pos.up();
            if (!mc.world.isAir(up)) continue;
            Box box = new Box(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
            if (!mc.world.getOtherEntities(null, box).isEmpty()) continue;
            Vec3d cc = new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            float td = dmg(cc, target), sd = dmg(cc, p);
            if (td < MIN_TARGET_DMG || sd > MAX_SELF_DMG || sd >= selfHp - 1) continue;
            double score = td - sd * 0.7;
            if (score > bestScore) { bestScore = score; bestPos = pos; }
        }
        if (bestPos != null) {
            Vec3d hitVec = new Vec3d(bestPos.getX() + 0.5, bestPos.getY() + 1.0, bestPos.getZ() + 0.5);
            mc.interactionManager.interactBlock(p, hand, new BlockHitResult(hitVec, Direction.UP, bestPos, false));
            p.swingHand(hand);
            crystalCd = 1;
        }
    }

    static Hand crystalHand(ClientPlayerEntity p) {
        if (p.getOffHandStack().isOf(Items.END_CRYSTAL)) return Hand.OFF_HAND;
        if (p.getMainHandStack().isOf(Items.END_CRYSTAL)) return Hand.MAIN_HAND;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(Items.END_CRYSTAL)) {
                p.getInventory().selectedSlot = i;
                return Hand.MAIN_HAND;
            }
        }
        return null;
    }

    static double distToBox(Vec3d p, Box b) {
        double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Yaklasik crystal patlama hasari (blok/engel hesaba katilmaz, zirh formulu vanilla). */
    static float dmg(Vec3d c, LivingEntity e) {
        double d = Math.sqrt(e.squaredDistanceTo(c)) / 12.0;
        if (d > 1.0) return 0f;
        double impact = 1.0 - d;
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        float armor = e.getArmor();
        float toughness = 2f;
        float f = 2f + toughness / 4f;
        float g = MathHelper.clamp(armor - dmg / f, armor * 0.2f, 20f);
        dmg *= 1f - g / 25f;
        return Math.max(dmg, 0f);
    }

    // =====================================================================
    // ESP TARAMA (StorageESP + BlockESP + base listesi)
    // =====================================================================
    static void scanTick() {
        if (mc.world != lastWorld) {
            storageHits.clear();
            blockHits.clear();
            queue.clear();
            lastWorld = mc.world;
            timer = 40;
        }
        if (queue.isEmpty() && ++timer >= 40) {
            timer = 0;
            int r = Math.min(mc.options.getViewDistance().getValue(), 16);
            ChunkPos pc = mc.player.getChunkPos();
            List<ChunkPos> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (mc.world.getChunkManager().getWorldChunk(pc.x + dx, pc.z + dz) != null)
                    list.add(new ChunkPos(pc.x + dx, pc.z + dz));
            }
            list.sort(Comparator.comparingInt(c -> (c.x - pc.x) * (c.x - pc.x) + (c.z - pc.z) * (c.z - pc.z)));
            queue.addAll(list);
        }
        for (int i = 0; i < 8 && !queue.isEmpty(); i++) scanChunk(queue.poll());
    }

    static void scanChunk(ChunkPos cp) {
        WorldChunk c = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z);
        if (c == null) return;
        long key = cp.toLong();

        if (Kuro.on[3]) {
            List<Hit> l = new ArrayList<>();
            for (BlockEntity be : c.getBlockEntities().values()) {
                int col = storageColor(be);
                if (col != 0) l.add(new Hit(be.getPos(), col));
            }
            if (l.isEmpty()) storageHits.remove(key); else storageHits.put(key, l);
        }

        if (Kuro.on[4]) {
            List<Hit> l = new ArrayList<>();
            ChunkSection[] secs = c.getSectionArray();
            int bottom = c.getBottomSectionCoord();
            for (int i = 0; i < secs.length && l.size() < MAX_HITS_PER_CHUNK; i++) {
                ChunkSection s = secs[i];
                if (s == null || s.isEmpty() || !s.hasAny(Mods::isTarget)) continue;
                int baseY = (bottom + i) << 4;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    BlockState st = s.getBlockState(x, y, z);
                    if (isTarget(st))
                        l.add(new Hit(new BlockPos(cp.getStartX() + x, baseY + y, cp.getStartZ() + z), blockColor(st)));
                }
            }
            if (l.isEmpty()) blockHits.remove(key); else blockHits.put(key, l);
        }
    }

    static int storageColor(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return 0xFF3030;
        if (be instanceof ChestBlockEntity) return 0xFFA020;
        if (be instanceof EnderChestBlockEntity) return 0xB040FF;
        if (be instanceof BarrelBlockEntity) return 0xA06030;
        if (be instanceof ShulkerBoxBlockEntity) return 0xFF50E0;
        if (be instanceof HopperBlockEntity) return 0x30D0FF;
        if (be instanceof AbstractFurnaceBlockEntity) return 0xA0A0A0;
        if (be instanceof DispenserBlockEntity) return 0x80FF80;
        return 0;
    }

    static boolean isTarget(BlockState st) {
        return targets.contains(st.getBlock()) || st.isIn(BlockTags.BEDS);
    }

    static int blockColor(BlockState st) {
        Block b = st.getBlock();
        if (b == Blocks.SPAWNER || b == Blocks.TRIAL_SPAWNER) return 0xFF2020;
        if (b == Blocks.ANCIENT_DEBRIS) return 0xC08040;
        if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) return 0x40FFFF;
        if (st.isIn(BlockTags.BEDS)) return 0xFFFF40;
        return 0xFFFFFF;
    }

    static void loadTargets() {
        String[] defaults = {
                "spawner", "trial_spawner", "beacon", "ancient_debris", "diamond_ore",
                "deepslate_diamond_ore", "enchanting_table", "end_portal_frame", "respawn_anchor"
        };
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("kuro_blocks.txt");
            if (!Files.exists(f)) {
                Files.writeString(f, "# BlockESP listesi: satir basina bir blok id (ornek: crafting_table)\n"
                        + String.join("\n", defaults) + "\n");
            }
            for (String line : Files.readAllLines(f)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Identifier id = Identifier.tryParse(line.contains(":") ? line : "minecraft:" + line);
                if (id != null && Registries.BLOCK.containsId(id)) targets.add(Registries.BLOCK.get(id));
            }
        } catch (IOException ignored) {
        }
    }

    // =====================================================================
    // BASE LISTESI (depo yogunluguna gore)
    // =====================================================================
    static void printBases() {
        if (mc.player == null || mc.world == null) return;
        if (storageHits.isEmpty()) {
            msg("Depo bulunamadi. StorageESP'yi ac (V) ve etrafta gez.");
            return;
        }
        Map<Long, Integer> score = new HashMap<>();
        for (long k : storageHits.keySet()) {
            ChunkPos c = new ChunkPos(k);
            int s = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                List<Hit> l = storageHits.get(new ChunkPos(c.x + dx, c.z + dz).toLong());
                if (l != null) s += l.size();
            }
            score.put(k, s);
        }
        List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(score.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        String dim = mc.world.getRegistryKey().getValue().getPath();
        List<ChunkPos> picked = new ArrayList<>();
        for (var e : sorted) {
            if (e.getValue() < MIN_BASE_SCORE || picked.size() >= 8) break;
            ChunkPos c = new ChunkPos(e.getKey());
            boolean near = false;
            for (ChunkPos q : picked) if (Math.abs(q.x - c.x) <= 3 && Math.abs(q.z - c.z) <= 3) near = true;
            if (near) continue;
            picked.add(c);
            msg("Base? x=" + c.getCenterX() + " z=" + c.getCenterZ() + " | " + e.getValue() + " depo | " + dim);
            logBase(dim, c, e.getValue());
        }
        if (picked.isEmpty()) msg("Yeterli yogunlukta depo kumesi yok (en az " + MIN_BASE_SCORE + " depo / 3x3 chunk).");
    }

    static void logBase(String dim, ChunkPos c, int score) {
        String key = dim + ":" + c.x + ":" + c.z;
        if (!logged.add(key)) return;
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("kuro_bases.txt");
            String line = LocalDateTime.now() + " " + dim + " x=" + c.getCenterX() + " z=" + c.getCenterZ()
                    + " depo=" + score + "\n";
            Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
