package com.donutsmp.addon.modules;

import com.donutsmp.addon.DonutSMPAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChunkFinder - Scans loaded chunks for base indicators and highlights
 * suspicious chunks. Bases on DonutSMP often leave traces such as
 * unnatural blocks, light sources, containers, and processed terrain.
 * Better than Glazed: multi-factor scoring per chunk, async-friendly,
 * thresholds tunable per indicator.
 */
public class ChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgIndicators = settings.createGroup("Indicators");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Radius in chunks around the player to scan (r = 0 means only the current chunk).")
        .defaultValue(4)
        .range(0, 8)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Integer> minScore = sgGeneral.add(new IntSetting.Builder()
        .name("min-score")
        .description("Minimum chunk score to be flagged as a possible base.")
        .defaultValue(3)
        .range(1, 50)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Integer> rescanTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-ticks")
        .description("How often (in ticks) to re-scan chunks. Lower = more responsive but heavier.")
        .defaultValue(40)
        .range(10, 200)
        .sliderRange(10, 200)
        .build()
    );

    // Indicator toggles & weights
    private final Setting<Boolean> checkUnnatural = sgIndicators.add(new BoolSetting.Builder()
        .name("unnatural-blocks")
        .description("Flag chunks containing crafted / placed blocks (planks, glass, bricks, etc.).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> unnaturalWeight = sgIndicators.add(new IntSetting.Builder()
        .name("unnatural-weight")
        .description("Score added per unnatural block found (capped per chunk).")
        .defaultValue(1)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> checkContainers = sgIndicators.add(new BoolSetting.Builder()
        .name("containers")
        .description("Flag chunks containing chests, barrels, shulker boxes, hoppers, etc.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> containerWeight = sgIndicators.add(new IntSetting.Builder()
        .name("container-weight")
        .description("Score added per container block found.")
        .defaultValue(5)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkLight = sgIndicators.add(new BoolSetting.Builder()
        .name("light-sources")
        .description("Flag chunks containing torches, lanterns, glowstone, etc.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> lightWeight = sgIndicators.add(new IntSetting.Builder()
        .name("light-weight")
        .description("Score added per light source found.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkTools = sgIndicators.add(new BoolSetting.Builder()
        .name("workstations")
        .description("Flag chunks with crafting tables, furnaces, anvils, enchanting tables, etc.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> toolWeight = sgIndicators.add(new IntSetting.Builder()
        .name("workstation-weight")
        .description("Score added per workstation block found.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkCrops = sgIndicators.add(new BoolSetting.Builder()
        .name("crops-farms")
        .description("Flag chunks containing farmland, crops, sugar cane, or animal-farm blocks.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> cropWeight = sgIndicators.add(new IntSetting.Builder()
        .name("crop-weight")
        .description("Score added per farm-related block found.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkWasLoaded = sgIndicators.add(new BoolSetting.Builder()
        .name("ignore-current")
        .description("Don't flag the chunk the player is currently standing in.")
        .defaultValue(true)
        .build()
    );

    // Render
    private final Setting<SettingColor> lowColor = sgRender.add(new ColorSetting.Builder()
        .name("low-score-color")
        .description("Color used for low-suspicion flagged chunks.")
        .defaultValue(new SettingColor(255, 255, 0, 80))
        .build()
    );
    private final Setting<SettingColor> midColor = sgRender.add(new ColorSetting.Builder()
        .name("mid-score-color")
        .description("Color used for medium-suspicion flagged chunks.")
        .defaultValue(new SettingColor(255, 128, 0, 120))
        .build()
    );
    private final Setting<SettingColor> highColor = sgRender.add(new ColorSetting.Builder()
        .name("high-score-color")
        .description("Color used for high-suspicion flagged chunks.")
        .defaultValue(new SettingColor(255, 0, 0, 180))
        .build()
    );
    private final Setting<Integer> midThreshold = sgRender.add(new IntSetting.Builder()
        .name("mid-threshold")
        .description("Score above which a chunk uses the mid color.")
        .defaultValue(8)
        .range(1, 200)
        .sliderRange(1, 200)
        .build()
    );
    private final Setting<Integer> highThreshold = sgRender.add(new IntSetting.Builder()
        .name("high-threshold")
        .description("Score above which a chunk uses the high color.")
        .defaultValue(20)
        .range(2, 500)
        .sliderRange(2, 500)
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the chunk boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Set<ChunkScore> flagged = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    public ChunkFinder() {
        super(DonutSMPAddon.CATEGORY, "chunk-finder", "Scans loaded chunks for indicators of a player base and highlights suspicious ones. Multi-factor scoring makes it more accurate than Glazed.");
    }

    @Override
    public void onDeactivate() {
        flagged.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        tickCounter++;
        if (tickCounter >= rescanTicks.get()) {
            tickCounter = 0;
            scan();
        }

        for (ChunkScore cs : flagged) {
            int cx = cs.x;
            int cz = cs.z;
            int minX = cx << 4;
            int minZ = cz << 4;
            int maxX = minX + 16;
            int maxZ = minZ + 16;

            SettingColor color;
            if (cs.score >= highThreshold.get()) color = highColor.get();
            else if (cs.score >= midThreshold.get()) color = midColor.get();
            else color = lowColor.get();

            // Full-height box from world bottom to a reasonable build height
            AABB box = new AABB(minX, mc.level.getMinBuildHeight(), minZ, maxX, mc.level.getMinBuildHeight() + 128, maxZ);
            event.renderer.box(box, color, color, shapeMode.get(), 0);
        }
    }

    private void scan() {
        flagged.clear();
        if (mc.level == null || mc.player == null) return;

        int centerCX = mc.player.blockPosition().getX() >> 4;
        int centerCZ = mc.player.blockPosition().getZ() >> 4;
        int radius = renderDistance.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerCX + dx;
                int cz = centerCZ + dz;

                if (checkWasLoaded.get() && dx == 0 && dz == 0) continue;

                if (!mc.level.isChunkLoaded(cx, cz)) continue;

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                int score = scoreChunk(chunk);
                if (score >= minScore.get()) {
                    flagged.add(new ChunkScore(cx, cz, score));
                }
            }
        }
    }

    private int scoreChunk(LevelChunk chunk) {
        int score = 0;
        int unnaturalCount = 0;
        int containerCount = 0;
        int lightCount = 0;
        int toolCount = 0;
        int cropCount = 0;
        // cap to avoid runaway values for very dense chunks
        int cap = 64;

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

                    if (state.isAir()) continue;

                    if (checkContainers.get() && isContainer(state)) {
                        containerCount++;
                    } else if (checkLight.get() && isLightSource(state)) {
                        lightCount++;
                    } else if (checkTools.get() && isWorkstation(state)) {
                        toolCount++;
                    } else if (checkCrops.get() && isFarmBlock(state)) {
                        cropCount++;
                    } else if (checkUnnatural.get() && isUnnatural(state)) {
                        unnaturalCount++;
                    }

                    if (containerCount + lightCount + toolCount + cropCount + unnaturalCount > cap * 5) break;
                }
            }
        }

        // weighted, each indicator capped so one mega-chunk doesn't dominate
        score += Math.min(unnaturalCount, cap) * unnaturalWeight.get();
        score += Math.min(containerCount, cap) * containerWeight.get();
        score += Math.min(lightCount, cap) * lightWeight.get();
        score += Math.min(toolCount, cap) * toolWeight.get();
        score += Math.min(cropCount, cap) * cropWeight.get();
        return score;
    }

    private boolean isContainer(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)
            || state.is(Blocks.SHULKER_BOX) || state.is(Blocks.HOPPER)
            || state.is(Blocks.DISPENSER) || state.is(Blocks.DROPPER);
    }

    private boolean isLightSource(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_TORCH)
            || state.is(Blocks.SOUL_WALL_TORCH) || state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN)
            || state.is(Blocks.GLOWSTONE) || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.JACK_O_LANTERN)
            || state.is(Blocks.END_ROD) || state.is(Blocks.OCHRE_FROGLIGHT) || state.is(Blocks.PEARLESCENT_FROGLIGHT)
            || state.is(Blocks.VERDANT_FROGLIGHT) || state.is(Blocks.GLOW_LICHEN)
            || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private boolean isWorkstation(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)
            || state.is(Blocks.SMOKER) || state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL)
            || state.is(Blocks.DAMAGED_ANVIL) || state.is(Blocks.ENCHANTING_TABLE) || state.is(Blocks.BREWING_STAND)
            || state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.LOOM) || state.is(Blocks.STONECUTTER)
            || state.is(Blocks.GRINDSTONE) || state.is(Blocks.CARTOGRAPHY_TABLE) || state.is(Blocks.FLETCHING_TABLE)
            || state.is(Blocks.LECTERN) || state.is(Blocks.COMPOSTER) || state.is(Blocks.CAULDRON);
    }

    private boolean isFarmBlock(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.is(Blocks.WHEAT) || state.is(Blocks.CARROTS)
            || state.is(Blocks.POTATOES) || state.is(Blocks.BEETROOTS) || state.is(Blocks.SUGAR_CANE)
            || state.is(Blocks.NETHER_WART) || state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)
            || state.is(Blocks.MELON_STEM) || state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    private boolean isUnnatural(BlockState state) {
        return state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_PLANKS) || state.is(Blocks.BIRCH_PLANKS)
            || state.is(Blocks.JUNGLE_PLANKS) || state.is(Blocks.ACACIA_PLANKS) || state.is(Blocks.DARK_OAK_PLANKS)
            || state.is(Blocks.MANGROVE_PLANKS) || state.is(Blocks.CHERRY_PLANKS) || state.is(Blocks.BAMBOO_PLANKS)
            || state.is(Blocks.CRIMSON_PLANKS) || state.is(Blocks.WARPED_PLANKS)
            || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
            || state.is(Blocks.BRICKS) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.DEEPSLATE_BRICKS)
            || state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
            || state.is(Blocks.QUARTZ_BLOCK) || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.STONE)
            || state.is(Blocks.SMOOTH_STONE_SLAB) || state.is(Blocks.OAK_SLAB) || state.is(Blocks.SPRUCE_SLAB)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.STONE_BRICK_SLAB)
            || state.is(Blocks.LADDER) || state.is(Blocks.OAK_FENCE) || state.is(Blocks.SPRUCE_FENCE)
            || state.is(Blocks.NETHER_BRICK_FENCE) || state.is(Blocks.OAK_DOOR) || state.is(Blocks.IRON_DOOR)
            || state.is(Blocks.OAK_TRAPDOOR) || state.is(Blocks.IRON_TRAPDOOR) || state.is(Blocks.IRON_BARS)
            || state.is(Blocks.WHITE_WOOL) || state.is(Blocks.WHITE_CARPET) || state.is(Blocks.WHITE_CONCRETE)
            || state.is(Blocks.WHITE_TERRACOTTA) || state.is(Blocks.WHITE_GLAZED_TERRACOTTA)
            || state.is(Blocks.BEDROCK) // portals often nearby
            || state.is(Blocks.OBSIDIAN) || state.is(Blocks.NETHER_PORTAL);
    }

    private static class ChunkScore {
        final int x;
        final int z;
        final int score;

        ChunkScore(int x, int z, int score) {
            this.x = x;
            this.z = z;
            this.score = score;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkScore)) return false;
            ChunkScore that = (ChunkScore) o;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }
}
