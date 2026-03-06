package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Clientside Visualizations plugin.
 * Provides a system for sending client-side visualizations to players:
 * - Debug visuals: Colored cuboid overlays for debugging regions
 * - Fake doors: Client-side block replacements (server blocks unchanged)
 * The system uses a ticker-based approach to keep visualizations persistent
 * without manual re-sending. Create a VisualizationManager to get started.
 */
public class ClientsideVisualsPlugin extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final Config<ClientsideVisualsConfig> config = withConfig("ClientsideVisuals", ClientsideVisualsConfig.CODEC);
    private static ClientsideVisualsConfig visConfig;

    public ClientsideVisualsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        ClientsideVisualizationHandler.initialize();
    }

    @Override
    protected void setup() {
        visConfig = config.get();
        Level debugLevel = visConfig.getDebugLevel();
        LOGGER.at(Level.INFO).log("[ClientsideVisuals] Setup complete (Debug: %s)", debugLevel);
        LOGGER.setLevel(debugLevel);
        config.save();
    }

    @Override
    protected void start() {
        LOGGER.at(Level.INFO).log("[Clientside] Started");
    }

    @Override
    protected void shutdown() {
        ClientsideVisualizationHandler.shutdown();
        LOGGER.at(Level.INFO).log("[Clientside] Shut down");
    }
}