package com.donutsmp.addon.modules;

import com.donutsmp.addon.DonutSMPAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NewChunkDetector - Keeps a persistent record of every chunk the player
 * has ever loaded (per world) and highlights chunks that have NOT been
 * loaded before in a distinct color.
 *
 * On DonutSMP this is essential for base hunting:
 *  - Newly encountered chunks may still hide undiscovered bases.
 *  - Chunks you've already searched are no longer "new" so you can move on.
 *  - You can quickly tell if a chunk has been touched or not.
 *
 * Records are saved to the game directory under
 *   .minecraft/donutsmp-addon/<worldId>.json
 * keyed by "<dimension>:<cx>:<cz>".
 */
public class NewChunkDetector extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> onlySurfaceChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("only-surface")
        .description("Only mark a chunk as 'new' if the player is above sea level when first seeing it (avoids marking caves).")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> newColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunk-color")
        .description("Color used for chunks you've never loaded before.")
        .defaultValue(new SettingColor(0, 255, 100, 110))
        .build()
    );

    private final Setting<SettingColor> knownColor = sgRender.add(new ColorSetting.Builder()
        .name("known-chunk-color")
        .description("Color used for chunks you've already visited.")
        .defaultValue(new SettingColor(60, 60, 60, 50))
        .build()
    );

    private final Setting<Integer> renderRadius = sgRender.add(new IntSetting.Builder()
        .name("render-radius")
        .description("Radius in chunks around the player to draw highlight boxes.")
        .defaultValue(8)
        .range(1, 16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the chunk boxes are rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Set<Long> known = ConcurrentHashMap.newKeySet();
    private final Set<Long> currentlyNew = ConcurrentHashMap.newKeySet(); // newly loaded, not yet recorded in render area
    private String worldId = "default";
    private boolean dirty = false;

    public NewChunkDetector() {
        super(DonutSMPAddon.CATEGORY, "new-chunk-detector", "Tracks every chunk you've ever visited (saved to disk) and highlights genuinely new/unexplored chunks. Lets you focus on unsearched terrain for base hunting.");
    }

    @Override
    public void onActivate() {
        known.clear();
        currentlyNew.clear();
        determineWorldId();
        loadFromDisk();
    }

    @Override
    public void onDeactivate() {
        if (dirty) saveToDisk();
        currentlyNew.clear();
    }

    private void determineWorldId() {
        if (mc.level == null || mc.player == null) {
            worldId = "default";
            return;
        }
        // Use the world registry key + dimension for uniqueness
        try {
            var id = mc.level.dimension().getValue();
            worldId = id.getNamespace() + "_" + id.getPath();
        } catch (Throwable t) {
            worldId = "default";
        }
    }

    private Path storageDir() {
        String mcDir = mc.runDirectory != null ? mc.runDirectory.getAbsolutePath() : ".";
        return Paths.get(mcDir, "donutsmp-addon");
    }

    private Path storageFile() {
        return storageDir().resolve(worldId + ".json");
    }

    private void loadFromDisk() {
        try {
            Path file = storageFile();
            if (!Files.exists(file)) return;
            String json = Files.readString(file);
            Gson g = new GsonBuilder().setLenient().create();
            Type t = new TypeToken<java.util.List<Long>>() {}.getType();
            java.util.List<Long> ll = g.fromJson(json, t);
            if (ll != null) known.addAll(ll);
        } catch (IOException | com.google.gson.JsonSyntaxException ignored) {
            // First run or corrupt file - just start fresh.
        }
    }

    private void saveToDisk() {
        try {
            Path dir = storageDir();
            Files.createDirectories(dir);
            Path file = storageFile();
            Gson g = new GsonBuilder().setLenient().setPrettyPrinting().create();
            Files.writeString(file, g.toJson(new java.util.ArrayList<>(known)));
            dirty = false;
        } catch (IOException ignored) {
            // Best effort - non-fatal
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        if (chunk == null) return;
        long cp = chunkKey(chunk);

        // For surface-only mode, ignore chunks when player is underground
        if (onlySurfaceChunks.get() && mc.player != null && mc.player.blockPosition().getY() < 60) {
            return;
        }

        boolean isNew = known.add(cp);
        if (isNew) {
            currentlyNew.add(cp);
            dirty = true;
            // Write incrementally every 64 new chunks to avoid losing too much progress on crash
            if (known.size() % 64 == 0) saveToDisk();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = renderRadius.get();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long cp = chunkKeyLong(cx, cz);

                boolean isNew = !known.contains(cp);
                int minX = cx << 4;
                int minZ = cz << 4;
                AABB box = new AABB(minX, mc.level.getMinBuildHeight(), minZ,
                    minX + 16, mc.level.getMinBuildHeight() + 64, minZ + 16);
                SettingColor color = isNew ? newColor.get() : knownColor.get();
                event.renderer.box(box, color, color, shapeMode.get(), 0);
            }
        }
    }

    private long chunkKey(LevelChunk chunk) {
        return chunkKeyLong(chunk.getPos().x, chunk.getPos().z);
    }

    private long chunkKeyLong(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
