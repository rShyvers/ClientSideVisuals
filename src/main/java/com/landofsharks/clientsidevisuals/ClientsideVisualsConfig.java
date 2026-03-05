package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Serializable configuration for the clientside visuals plugin.
 *
 * <p>Loaded and persisted via {@link #CODEC}. The only current option is the debug logging
 * level, which controls the verbosity of plugin log output at runtime.
 *
 * <p>Instances are constructed by the codec during deserialization; use {@link #ClientsideVisualsConfig()}
 * directly only in tests or when defaults are acceptable.
 */
final class ClientsideVisualsConfig {
    /**
     * Codec used to serialize and deserialize {@link ClientsideVisualsConfig} instances.
     * Maps the {@code "DebugLevel"} key to {@link #debugLevel} using a standard integer codec.
     */
    static final BuilderCodec<ClientsideVisualsConfig> CODEC = BuilderCodec.builder(ClientsideVisualsConfig.class, ClientsideVisualsConfig::new)
        .append(
            new KeyedCodec<>("DebugLevel", Codec.INTEGER),
            (config, value) -> config.debugLevel = value,
            config -> config.debugLevel
        )
        .documentation("Debug visualization level - Valid values: 0 (OFF), 1 (SEVERE), 2 (WARNING), 3 (INFO), 4 (CONFIG), 5 (FINE), 6 (FINER), 7 (FINEST), 8 (ALL), default: 2 (WARNING)")
        .add()
        .build();
    
    /**
     * Raw integer logging level as stored in the config file.
     * Mapped to a {@link Level} instance by {@link #getDebugLevel()}.
     * Defaults to {@code 2} ({@link Level#WARNING}).
     */
    private int debugLevel = 2;

    /**
     * Construct a config instance with all values set to their defaults.
     * Intended for use by {@link #CODEC} during deserialization and for default-config creation.
     */
    ClientsideVisualsConfig() {
    }

    /**
     * Return the configured logging level as a {@link Level} instance.
     * The integer stored in the config file is mapped as follows:
     *
     * <table>
     *   <tr><th>Value</th><th>Level</th></tr>
     *   <tr><td>0</td><td>{@link Level#OFF}</td></tr>
     *   <tr><td>1</td><td>{@link Level#SEVERE}</td></tr>
     *   <tr><td>2</td><td>{@link Level#WARNING} (default)</td></tr>
     *   <tr><td>3</td><td>{@link Level#INFO}</td></tr>
     *   <tr><td>4</td><td>{@link Level#CONFIG}</td></tr>
     *   <tr><td>5</td><td>{@link Level#FINE}</td></tr>
     *   <tr><td>6</td><td>{@link Level#FINER}</td></tr>
     *   <tr><td>7</td><td>{@link Level#FINEST}</td></tr>
     *   <tr><td>8</td><td>{@link Level#ALL}</td></tr>
     * </table>
     *
     * Any value outside the range {@code [0, 8]} falls back to {@link Level#WARNING}.
     *
     * @return the resolved {@link Level}; never {@code null}
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
     * Set the debug logging level using the integer encoding described in {@link #getDebugLevel()}.
     * Values outside the range {@code [0, 8]} are accepted and stored, but {@link #getDebugLevel()}
     * will fall back to {@link Level#WARNING} when they are read.
     *
     * @param debugLevel integer level to store
     */
    void setDebugLevel(int debugLevel) {
        this.debugLevel = debugLevel;
    }
}