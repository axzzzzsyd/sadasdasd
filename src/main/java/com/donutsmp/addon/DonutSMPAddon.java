package com.donutsmp.addon;

import com.donutsmp.addon.modules.ChunkFinder;
import com.donutsmp.addon.modules.ClusterFinder;
import com.donutsmp.addon.modules.NewChunkDetector;
import com.donutsmp.addon.modules.StashDetector;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class DonutSMPAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("DonutSMP");

    @Override
    public void onInitialize() {
        LOG.info("Initializing DonutSMP Addon - Axz debug");

        // Base hunting modules
        Modules.get().add(new ChunkFinder());
        Modules.get().add(new ClusterFinder());
        Modules.get().add(new StashDetector());
        Modules.get().add(new NewChunkDetector());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.donutsmp.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Axz", "donutsmp-addon");
    }
}
