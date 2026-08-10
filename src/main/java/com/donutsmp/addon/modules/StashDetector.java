package com.donutsmp.addon.modules;

import com.donutsmp.addon.DonutSMPAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StashDetector - Looks for hidden stashes.
 *
 * A "stash" on a server like DonutSMP is typically a small cluster of
 * containers placed in an otherwise unremarkable location: a single
 * underground cavity, the corner of a savanna biome, an unmarked
 * hole in a cave, etc. StashDetector scans loaded chunks and flags
 * any chunk that contains a small concentration of container blocks
 * WITHOUT the usual base-building context (no planks, slabs, doors,
 * torches nearby). This catches stashes that chunk-finder might score
 * low because they leave no other traces.
 */
public class StashDetector extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThresholds = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Radius in chunks around the player to scan.")
        .defaultValue(6)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> rescanTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-ticks")
        .description("How often (in ticks) to re-scan chunks.")
        .defaultValue(40)
        .range(10, 200)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Integer> minContainers = sgThresholds.add(new IntSetting.Builder()
        .name("min-containers")
        .description("Minimum containers in a chunk to be considered a stash.")
        .defaultValue(2)
        .range(1, 32)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> maxBuildBlocks = sgThresholds.add(new IntSetting.Builder()
        .name("max-build-blocks")
        .description("If a chunk has more than this many obvious building blocks it's a real base, not a stash. Skip it.")
        .defaultValue(32)
        .range(0, 512)
        .sliderRange(0, 512)
        .build()
    );

    private final Setting<Boolean> ignorePlayersChunk = sgThresholds.add(new BoolSetting.Builder()
        .name("ignore-own-chunk")
        .description("Don't flag the chunk you are standing in (you may be carrying containers).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeEnderChests = sgThresholds.add(new BoolSetting.Builder()
        .name("ender-chests")
        .description("Include ender chests as stash indicators (often placed near hidden loot).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> boxColor = sgRender.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Color of the stash chunk highlight.")
        .defaultValue(new SettingColor(255, 60, 180, 130))
        .build()
    );

    private final Setting<SettingColor> containerColor = sgRender.add(new ColorSetting.Builder()
        .name("container-color")
        .description("Outline color drawn around the actual containers in a flagged chunk.")
        .defaultValue(new SettingColor(0, 255, 200, 220))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Set<BlockPos> containerPositions = ConcurrentHashMap.newKeySet();
    private final Set<ChunkXZ> flaggedChunks = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    public StashDetector() {
        super(DonutSMPAddon.CATEGORY, "stash-detector", "Detects hidden stashes: chunks with a few containers but few other building blocks. Catches loot caches that ChunkFinder would miss.");
    }

    @Override
    public void onDeactivate() {
        containerPositions.clear();
        flaggedChunks.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        tickCounter++;
        if (tickCounter >= rescanTicks.get()) {
            tickCounter = 0;
            scan();
        }

        for (ChunkXZ c : flaggedChunks) {
            int minX = c.x << 4;
            int minZ = c.z << 4;
            AABB box = new AABB(minX, mc.level.getMinBuildHeight(), minZ,
                minX + 16, mc.level.getMinBuildHeight() + 64, minZ + 16);
            event.renderer.box(box, boxColor.get(), boxColor.get(), shapeMode.get(), 0);
        }

        for (BlockPos pos : containerPositions) {
            AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            event.renderer.box(box, containerColor.get(), containerColor.get(), shapeMode.get(), 0);
        }
    }

    private void scan() {
        containerPositions.clear();
        flaggedChunks.clear();
        if (mc.level == null || mc.player == null) return;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int radius = scanRadius.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (ignorePlayersChunk.get() && dx == 0 && dz == 0) continue;
                if (!mc.level.isChunkLoaded(cx, cz)) continue;

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                int containerCount = 0;
                int buildCount = 0;
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                int minX = chunk.getPos().getMinBlockX();
                int minZ = chunk.getPos().getMinBlockZ();
                int minY = mc.level.getMinBuildHeight();
                int maxY = Math.min(mc.level.getMaxY(), minY + 128);

                for (int x = minX; x < minX + 16 && containerCount <= minContainers.get(); x++) {
                    for (int z = minZ; z < minZ + 16; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            if (containerCount > minContainers.get() * 4) break;
                            pos.set(x, y, z);
                            BlockState s = chunk.getBlockState(pos);
                            if (isContainer(s)) {
                                containerCount++;
                            } else if (isBuildBlock(s)) {
                                buildCount++;
                            }
                        }
                    }
                }

                if (containerCount >= minContainers.get() && buildCount <= maxBuildBlocks.get()) {
                    flaggedChunks.add(new ChunkXZ(cx, cz));
                    // Collect container positions for this chunk into the render set (capped)
                    int added = 0;
                    for (int x = minX; x < minX + 16 && added < 64; x++) {
                        for (int z = minZ; z < minZ + 16 && added < 64; z++) {
                            for (int y = minY; y <= maxY && added < 64; y++) {
                                pos.set(x, y, z);
                                BlockState s = chunk.getBlockState(pos);
                                if (isContainer(s)) {
                                    containerPositions.add(new BlockPos(x, y, z));
                                    added++;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isContainer(BlockState s) {
        if (s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.BARREL)
            || s.is(Blocks.SHULKER_BOX) || s.is(Blocks.HOPPER) || s.is(Blocks.DISPENSER)
            || s.is(Blocks.DROPPER)) return true;
        if (includeEnderChests.get() && s.is(Blocks.ENDER_CHEST)) return true;
        // colored shulker boxes
        return isShulkerBoxFamily(s);
    }

    private boolean isShulkerBoxFamily(BlockState s) {
        return s.is(Blocks.WHITE_SHULKER_BOX) || s.is(Blocks.ORANGE_SHULKER_BOX)
            || s.is(Blocks.MAGENTA_SHULKER_BOX) || s.is(Blocks.LIGHT_BLUE_SHULKER_BOX)
            || s.is(Blocks.YELLOW_SHULKER_BOX) || s.is(Blocks.LIME_SHULKER_BOX)
            || s.is(Blocks.PINK_SHULKER_BOX) || s.is(Blocks.GRAY_SHULKER_BOX)
            || s.is(Blocks.LIGHT_GRAY_SHULKER_BOX) || s.is(Blocks.CYAN_SHULKER_BOX)
            || s.is(Blocks.PURPLE_SHULKER_BOX) || s.is(Blocks.BLUE_SHULKER_BOX)
            || s.is(Blocks.BROWN_SHULKER_BOX) || s.is(Blocks.GREEN_SHULKER_BOX)
            || s.is(Blocks.RED_SHULKER_BOX) || s.is(Blocks.BLACK_SHULKER_BOX);
    }

    private boolean isBuildBlock(BlockState s) {
        return s.is(Blocks.OAK_PLANKS) || s.is(Blocks.SPRUCE_PLANKS) || s.is(Blocks.BIRCH_PLANKS)
            || s.is(Blocks.JUNGLE_PLANKS) || s.is(Blocks.ACACIA_PLANKS) || s.is(Blocks.DARK_OAK_PLANKS)
            || s.is(Blocks.MANGROVE_PLANKS) || s.is(Blocks.CHERRY_PLANKS) || s.is(Blocks.BAMBOO_PLANKS)
            || s.is(Blocks.CRIMSON_PLANKS) || s.is(Blocks.WARPED_PLANKS)
            || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.BRICKS) || s.is(Blocks.NETHER_BRICKS)
            || s.is(Blocks.COBBLESTONE) || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.SANDSTONE)
            || s.is(Blocks.GLASS) || s.is(Blocks.GLASS_PANE) || s.is(Blocks.OAK_DOOR)
            || s.is(Blocks.IRON_DOOR) || s.is(Blocks.OAK_FENCE) || s.is(Blocks.SPRUCE_FENCE)
            || s.is(Blocks.LADDER) || s.is(Blocks.OAK_STAIRS) || s.is(Blocks.COBBLESTONE_STAIRS)
            || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN)
            || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE);
    }

    private static class ChunkXZ {
        final int x;
        final int z;
        ChunkXZ(int x, int z) { this.x = x; this.z = z; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkXZ)) return false;
            ChunkXZ c = (ChunkXZ) o;
            return x == c.x && z == c.z;
        }
        @Override public int hashCode() { return 31 * x + z; }
    }
}
