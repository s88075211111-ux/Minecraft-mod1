package com.example.aicraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Собирает наблюдение. Вызывать ТОЛЬКО из главного потока клиента. */
public final class Observation {
    private Observation() {
    }

    private static final int RADIUS = 6;
    private static final Set<String> SPECIAL = new HashSet<>(Arrays.asList(
            "chest", "trapped_chest", "ender_chest", "barrel", "crafting_table", "furnace", "blast_furnace",
            "smoker", "anvil", "enchanting_table", "brewing_stand", "spawner", "obsidian", "nether_portal",
            "end_portal_frame", "tnt", "fire", "soul_fire", "magma_block", "cactus", "sweet_berry_bush",
            "sugar_cane", "pumpkin", "melon", "bookshelf", "water", "lava"));

    public static JsonObject build(MinecraftClient mc) {
        JsonObject o = new JsonObject();
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) {
            o.addProperty("error", "not in a world");
            return o;
        }
        BlockPos bp = p.getBlockPos();

        o.add("pos", nums(r1(p.getX()), r1(p.getY()), r1(p.getZ())));
        o.add("block", ints(bp.getX(), bp.getY(), bp.getZ()));
        o.addProperty("yaw", Math.round(MathHelper.wrapDegrees(p.getYaw())));
        o.addProperty("pitch", Math.round(p.getPitch()));
        o.addProperty("facing", p.getHorizontalFacing().asString());
        o.addProperty("hp", r1(p.getHealth()));
        o.addProperty("food", p.getHungerManager().getFoodLevel());
        o.addProperty("dimension", shortId(w.getRegistryKey().getValue()));
        o.addProperty("biome", biome(w, bp));
        long t = w.getTimeOfDay() % 24000L;
        o.addProperty("time", t);
        o.addProperty("daytime", t < 12000L ? "day" : (t < 13000L ? "dusk" : (t < 23000L ? "night" : "dawn")));
        o.addProperty("on_ground", p.isOnGround());
        if (p.isTouchingWater()) {
            o.addProperty("in_water", true);
        }
        if (mc.currentScreen != null) {
            o.addProperty("screen", mc.currentScreen.getClass().getSimpleName());
        }

        // --- инвентарь ---
        PlayerInventory inv = p.getInventory();
        o.addProperty("hand", stack(p.getMainHandStack()));
        o.addProperty("slot", inv.selectedSlot + 1);
        JsonArray hot = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hot.add((i + 1) + ":" + stack(inv.getStack(i)));
        }
        o.add("hotbar", hot);
        Map<String, Integer> tot = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                tot.merge(itemId(s), s.getCount(), Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(tot.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        JsonObject invJ = new JsonObject();
        int n = 0;
        for (Map.Entry<String, Integer> e : es) {
            if (n++ >= 25) break;
            invJ.addProperty(e.getKey(), e.getValue());
        }
        o.add("inv", invJ);
        JsonArray armor = new JsonArray();
        for (int i = 0; i < 4; i++) {
            ItemStack s = inv.getArmorStack(i);
            if (!s.isEmpty()) armor.add(itemId(s));
        }
        if (armor.size() > 0) o.add("armor", armor);
        if (!p.getOffHandStack().isEmpty()) o.addProperty("offhand", stack(p.getOffHandStack()));

        // --- на что смотрю ---
        JsonObject look = new JsonObject();
        HitResult cross = mc.crosshairTarget;
        if (cross != null && cross.getType() == HitResult.Type.ENTITY) {
            Entity e = ((EntityHitResult) cross).getEntity();
            look.addProperty("entity", entityName(e));
            look.add("at", ints(MathHelper.floor(e.getX()), MathHelper.floor(e.getY()), MathHelper.floor(e.getZ())));
            look.addProperty("dist", r1(p.distanceTo(e)));
        } else {
            HitResult hr = p.raycast(32.0, 1.0F, false);
            if (hr.getType() == HitResult.Type.BLOCK) {
                BlockPos q = ((BlockHitResult) hr).getBlockPos();
                look.addProperty("block", blockId(w, q));
                look.add("at", ints(q.getX(), q.getY(), q.getZ()));
                look.addProperty("dist", r1(p.getEyePos().distanceTo(hr.getPos())));
            } else {
                look.addProperty("block", "none");
            }
        }
        o.add("looking_at", look);

        o.addProperty("standing_on", blockId(w, bp.down()));
        BlockPos ahead = bp.offset(p.getHorizontalFacing());
        JsonArray front = new JsonArray();
        front.add(blockId(w, ahead));
        front.add(blockId(w, ahead.up()));
        o.add("front", front);

        // --- интересные блоки вокруг ---
        List<Object[]> found = new ArrayList<>();
        Map<String, Integer> cnt = new HashMap<>();
        double lavaDist = 99.0;
        BlockPos lavaPos = null;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    m.set(bp.getX() + dx, bp.getY() + dy, bp.getZ() + dz);
                    BlockState s = w.getBlockState(m);
                    if (s.isAir()) continue;
                    String k = kind(s);
                    if (k == null) continue;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (k.equals("lava") && Math.sqrt(d2) < lavaDist) {
                        lavaDist = Math.sqrt(d2);
                        lavaPos = m.toImmutable();
                    }
                    found.add(new Object[]{d2, k, m.getX(), m.getY(), m.getZ()});
                }
            }
        }
        found.sort(Comparator.comparingDouble(a -> (Double) a[0]));
        JsonArray blocks = new JsonArray();
        for (Object[] f : found) {
            if (blocks.size() >= 30) break;
            String k = (String) f[1];
            int c = cnt.merge(k, 1, Integer::sum);
            int cap = k.equals("water") ? 3 : 8;
            if (c > cap) continue;
            blocks.add(k + " " + f[2] + " " + f[3] + " " + f[4]);
        }
        o.add("blocks", blocks);

        // --- сущности ---
        List<LivingEntity> living = w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(16.0),
                e -> e != p && e.isAlive());
        living.sort(Comparator.comparingDouble(e -> p.squaredDistanceTo(e)));
        JsonArray ents = new JsonArray();
        int hostiles = 0;
        JsonArray danger = new JsonArray();
        for (LivingEntity e : living) {
            if (ents.size() >= 10) break;
            JsonObject j = new JsonObject();
            j.addProperty("type", entityName(e));
            j.addProperty("dist", r1(p.distanceTo(e)));
            j.addProperty("hp", r1(e.getHealth()));
            j.add("at", ints(MathHelper.floor(e.getX()), MathHelper.floor(e.getY()), MathHelper.floor(e.getZ())));
            if (e instanceof Monster) {
                j.addProperty("hostile", true);
                if (hostiles++ < 3 && p.distanceTo(e) < 12.0F) {
                    danger.add("hostile " + entityName(e) + " " + r1(p.distanceTo(e)) + " blocks away");
                }
            }
            ents.add(j);
        }
        o.add("entities", ents);

        List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(12.0), e -> true);
        drops.sort(Comparator.comparingDouble(e -> p.squaredDistanceTo(e)));
        JsonArray dr = new JsonArray();
        for (ItemEntity e : drops) {
            if (dr.size() >= 6) break;
            dr.add(itemId(e.getStack()) + " x" + e.getStack().getCount() + " "
                    + MathHelper.floor(e.getX()) + " " + MathHelper.floor(e.getY()) + " " + MathHelper.floor(e.getZ()));
        }
        o.add("drops", dr);

        // --- опасности ---
        if (p.getHealth() <= 6.0F) danger.add("low health");
        if (p.getHungerManager().getFoodLevel() <= 6) danger.add("hungry");
        if (p.isOnFire()) danger.add("on fire");
        if (p.isSubmergedInWater() && p.getAir() < 150) danger.add("drowning risk");
        if (lavaPos != null && lavaDist <= 5.0) {
            danger.add("lava " + r1(lavaDist) + " blocks away at " + lavaPos.getX() + " " + lavaPos.getY() + " " + lavaPos.getZ());
        }
        String h = hazardAt(w, ahead.getX(), ahead.getY(), ahead.getZ());
        if (h != null) danger.add(h + " directly ahead");
        o.add("danger", danger);

        JsonArray chat = new JsonArray();
        for (String s : AiCraft.recentChat(5)) {
            chat.add(s);
        }
        o.add("chat", chat);
        return o;
    }

    /** Опасность в клетке (bx,by,bz), где окажутся ноги игрока; null = безопасно или просто препятствие. */
    public static String hazardAt(World w, int bx, int by, int bz) {
        BlockPos feetPos = new BlockPos(bx, by, bz);
        BlockState feet = w.getBlockState(feetPos);
        if (feet.getFluidState().isIn(FluidTags.LAVA)) return "lava";
        if (feet.isOf(Blocks.FIRE) || feet.isOf(Blocks.SOUL_FIRE)) return "fire";
        if (!feet.getCollisionShape(w, feetPos).isEmpty()) return null;
        if (w.getBlockState(feetPos.up()).getFluidState().isIn(FluidTags.LAVA)) return "lava";
        for (int k = 1; k <= 4; k++) {
            BlockPos q = new BlockPos(bx, by - k, bz);
            BlockState s = w.getBlockState(q);
            if (s.getFluidState().isIn(FluidTags.LAVA)) return "lava";
            if (s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS)) return "magma/cactus";
            if (!s.getCollisionShape(w, q).isEmpty() || !s.getFluidState().isEmpty()) return null;
        }
        return "cliff (drop of 4+ blocks)";
    }

    private static String kind(BlockState s) {
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (path.endsWith("_ore") || path.equals("ancient_debris") || SPECIAL.contains(path)) return path;
        if (s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.BEDS) || s.isIn(BlockTags.CROPS)) return path;
        return null;
    }

    public static String blockId(World w, BlockPos pos) {
        return shortId(Registries.BLOCK.getId(w.getBlockState(pos).getBlock()));
    }

    public static String itemId(ItemStack s) {
        return shortId(Registries.ITEM.getId(s.getItem()));
    }

    public static String stack(ItemStack s) {
        return s.isEmpty() ? "empty" : itemId(s) + " x" + s.getCount();
    }

    public static String entityName(Entity e) {
        if (e instanceof PlayerEntity) {
            return "player:" + e.getName().getString();
        }
        return shortId(Registries.ENTITY_TYPE.getId(e.getType()));
    }

    public static String shortId(Identifier id) {
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private static String biome(World w, BlockPos pos) {
        try {
            return w.getBiome(pos).getKey().map(k -> shortId(k.getValue())).orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static JsonArray nums(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }

    private static JsonArray ints(int... v) {
        JsonArray a = new JsonArray();
        for (int d : v) a.add(d);
        return a;
    }
}
