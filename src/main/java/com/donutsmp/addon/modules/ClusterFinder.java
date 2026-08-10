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
 * ClusterFinder - Highlights fully grown amethyst clusters in loaded chunks.
 *
 * On DonutSMP, players run amethyst farms which leave behind rows of fully
 * grown clusters on budding amethyst. Finding these is a strong indicator
 * of a nearby base / storage room. This module only flags
 * {@link net.minecraft.world.level.block.Blocks#AMETHYST_CLUSTER} (the final
 * growth stage). Optionally also count budding-amethyst blocks for context.
 */
public class ClusterFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
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
        .defaultValue(30)
        .range(5, 200)
        .sliderRange(5, 200)
        .build()
    );

    private final Setting<Integer> minClusters = sgGeneral.add(new IntSetting.Builder()
        .name("min-clusters-per-chunk")
        .description("Only highlight a chunk if it contains at least this many fully grown clusters.")
        .defaultValue(2)
        .range(1, 64)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> showIndividual = sgGeneral.add(new BoolSetting.Builder()
        .name("show-each-cluster")
        .description("Also render a small box on every individual fully-grown cluster, not just the chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBudding = sgGeneral.add(new BoolSetting.Builder()
        .name("show-budding")
        .description("Also draw boxes on budding-amethyst blocks (the source blocks).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color of the highlighted chunk box.")
        .defaultValue(new SettingColor(160, 80, 255, 90))
        .build()
    );

    private final Setting<SettingColor> clusterColor = sgRender.add(new ColorSetting.Builder()
        .name("cluster-color")
        .description("Color of the per-cluster highlight boxes.")
        .defaultValue(new SettingColor(200, 120, 255, 200))
        .build()
    );

    private final Setting<SettingColor> buddingColor = sgRender.add(new ColorSetting.Builder()
        .name("budding-color")
        .description("Color of the budding-amethyst highlight boxes.")
        .defaultValue(new SettingColor(120, 60, 200, 180))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Set<BlockPos> clusters = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> budding = ConcurrentHashMap.newKeySet();

    public ClusterFinder() {
        super(DonutSMPAddon.CATEGORY, "cluster-finder", "Highlights fully grown amethyst clusters and chunks containing many of them. Great for spotting amethyst farms near hidden bases.");
    }

    @Override
    public void onDeactivate() {
        clusters.clear();
        budding.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Rescan handled via tick-based throttle done outside the renderer normally,
        // but doing it here keeps things simple and responsive without registering another handler.
        scanIfNeeded();

        // Per-cluster small boxes
        if (showIndividual.get()) {
            for (BlockPos pos : clusters) {
                AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                event.renderer.box(box, clusterColor.get(), clusterColor.get(), shapeMode.get(), 0);
            }
        }

        // Per-budding-amethyst boxes
        if (showBudding.get()) {
            for (BlockPos pos : budding) {
                AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                event.renderer.box(box, buddingColor.get(), buddingColor.get(), shapeMode.get(), 0);
            }
        }

        // Per-chunk box (only chunks with >= minClusters clusters)
        java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
        for (BlockPos p : clusters) {
            long cp = net.minecraft.world.level.ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
            counts.merge(cp, 1, Integer::sum);
        }
        for (java.util.Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() < minClusters.get()) continue;
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(e.getKey());
            int minX = cp.getMinBlockX();
            int minZ = cp.getMinBlockZ();
            AABB box = new AABB(minX, mc.level.getMinBuildHeight(), minZ,
                minX + 16, mc.level.getMinBuildHeight() + 64, minZ + 16);
            event.renderer.box(box, chunkColor.get(), chunkColor.get(), shapeMode.get(), 0);
        }
    }

    private int tickThrottle = 0;
    private void scanIfNeeded() {
        tickThrottle++;
        if (tickThrottle < rescanTicks.get() && !clusters.isEmpty()) return;
        tickThrottle = 0;
        scan();
    }

    private void scan() {
        clusters.clear();
        budding.clear();
        if (mc.level == null || mc.player == null) return;

        int centerCX = mc.player.blockPosition().getX() >> 4;
        int centerCZ = mc.player.blockPosition().getZ() >> 4;
        int radius = scanRadius.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerCX + dx;
                int cz = centerCZ + dz;
                if (!mc.level.isChunkLoaded(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);

                int minX = chunk.getPos().getMinBlockX();
                int minZ = chunk.getPos().getMinBlockZ();
                int minY = mc.level.getMinBuildHeight();
                int maxY = Math.min(mc.level.getMaxY(), minY + 128);

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int x = minX; x < minX + 16; x++) {
                    for (int z = minZ; z < minZ + 16; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (state.is(Blocks.AMETHYST_CLUSTER)) {
                                clusters.add(new BlockPos(x, y, z));
                            } else if (showBudding.get() && state.is(Blocks.BUDDING_AMETHYST)) {
                                budding.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
    }
}
