package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;
import java.util.logging.Level;

final class ClientsideVisualsConfig {

    static final BuilderCodec<ClientsideVisualsConfig> CODEC = BuilderCodec.builder(ClientsideVisualsConfig.class, ClientsideVisualsConfig::new)
        .append(
            new KeyedCodec<>("DebugLevel", Codec.INTEGER),
            (config, value) -> config.debugLevel = value,
            config -> config.debugLevel
        )
        .documentation("Debug visualization level - Valid values: 0 (OFF), 1 (SEVERE), 2 (WARNING), 3 (INFO), 4 (CONFIG), 5 (FINE), 6 (FINER), 7 (FINEST), 8 (ALL), default: 2 (WARNING)")
        .add()
        .build();

    private int debugLevel = 2;

    ClientsideVisualsConfig() {
    }

    /**
     * Get the configured debug logging level.
     * 
     * @return The logging level
     */
    @Nonnull
    Level getDebugLevel() {
        return switch (debugLevel) {
            case 0 -> Level.OFF;
            case 1 -> Level.SEVERE;
            case 2 -> Level.WARNING;
            case 3 -> Level.INFO;
            case 4 -> Level.CONFIG;
            case 5 -> Level.FINE;
            case 6 -> Level.FINER;
            case 7 -> Level.FINEST;
            case 8 -> Level.ALL;
            default -> Level.WARNING;
        };
    }

    /**
     * Set the debug logging level.
     * 
     * @param debugLevel Integer level (0=OFF, 1=SEVERE, 2=WARNING, 3=INFO, 4=CONFIG, 5=FINE, 6=FINER, 7=FINEST, 8=ALL)
     */
    void setDebugLevel(int debugLevel) {
        this.debugLevel = debugLevel;
    }
}