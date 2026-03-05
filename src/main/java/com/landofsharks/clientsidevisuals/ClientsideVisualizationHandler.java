package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlock;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Central handler for client-side visualizations.
 *
 * <p>This class owns the visualization system lifecycle and provides low-level packet delivery
 * for client-side visuals. It is not intended to be used directly by game systems; instead,
 * systems should create and manage a {@link VisualizationManager} and register it here via
 * {@link #registerManager}.
 *
 * <p>The static {@link ClientsideVisualizerService} instance is created on {@link #initialize()}
 * and destroyed on {@link #shutdown()}. All public mutating methods guard against a {@code null}
 * service and are safe to call before initialization or after shutdown.
 *
 * <h2>Thread safety</h2>
 * {@link #initialize()}, {@link #shutdown()}, {@link #registerManager}, and
 * {@link #unregisterManager} are {@code synchronized} on the class and safe to call from any
 * thread. Internal helpers ({@link #applyVectorSets}, {@link #revertFakeDoors}) are called from
 * the ticker thread owned by {@link ClientsideVisualizerService}.
 */
public final class ClientsideVisualizationHandler {

    public static final Vector3f GREEN = new Vector3f(0.0F, 1.0F, 0.0F);
    public static final Vector3f ORANGE = new Vector3f(1.0F, 0.5F, 0.0F);
    public static final Vector3f RED = new Vector3f(1.0F, 0.0F, 0.0F);

    @Nullable
    private static volatile ClientsideVisualizerService visualizerService;

    /**
     * Represents a set of block positions with associated rendering metadata.
     * A {@code VectorSet} is either a debug visual (colored wireframe cuboids) or a fake-door
     * (client-side block replacement). Use the static factory methods to construct instances.
     *
     * @see VectorSet#debugVisual
     * @see VectorSet#fakeDoors
     */
    public static final class VectorSet {
        /**
         * The positions at which this set will be rendered or to which fake blocks will be sent.
         */
        @Nonnull
        private final Vector3i[] positions;

        /**
         * For {@link VectorSetType#FAKE_DOORS}: the world positions to read actual block data from.
         * {@code null} for {@link VectorSetType#DEBUG_VISUAL}.
         */
        @Nullable
        private final Vector3i[] destinationPositions;
        /**
         * For {@link VectorSetType#DEBUG_VISUAL}: the RGB wireframe color.
         * {@code null} for {@link VectorSetType#FAKE_DOORS}.
         */
        @Nullable
        private final Vector3f debugColor;
        @Nonnull
        private final VectorSetType type;

        private VectorSet(@Nonnull Vector3i[] positions, @Nullable Vector3i[] destinationPositions, 
                         @Nullable Vector3f debugColor, @Nonnull VectorSetType type) {
            this.positions = positions;
            this.destinationPositions = destinationPositions;
            this.debugColor = debugColor;
            this.type = type;
        }

        /**
         * Return the positions at which this set will be rendered or to which fake blocks will be sent.
         *
         * @return array of block positions; never {@code null}
         */
        @Nonnull
        public Vector3i[] getPositions() {
            return positions;
        }

        /**
         * Return the source positions used to read real block data for fake-door sets.
         * For each index {@code i}, the block found at {@code destinationPositions[i]} is sent
         * to the client at {@code positions[i]}.
         *
         * @return array of source positions, or {@code null} for {@link VectorSetType#DEBUG_VISUAL}
         */
        @Nullable
        public Vector3i[] getDestinationPositions() {
            return destinationPositions;
        }

        /**
         * Return the wireframe color used when rendering this set as debug cuboids.
         * Each component is in the range {@code [0.0, 1.0]}.
         *
         * @return the RGB color, or {@code null} for {@link VectorSetType#FAKE_DOORS}
         */
        @Nullable
        public Vector3f getDebugColor() {
            return debugColor;
        }

        /**
         * Return the type of this vector set.
         *
         * @return the {@link VectorSetType}; never {@code null}
         */
        @Nonnull
        public VectorSetType getType() {
            return type;
        }

        /**
         * Create a {@link VectorSetType#DEBUG_VISUAL} set that renders colored wireframe cuboids
         * at the given positions. Adjacent positions are automatically merged into larger AABBs
         * by the rendering pipeline.
         *
         * @param positions block positions to visualize
         * @param color     RGB color with each component in the range {@code [0.0, 1.0]}
         * @return a new debug-visual {@code VectorSet}
         */
        @Nonnull
        public static VectorSet debugVisual(@Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
            return new VectorSet(positions, null, color, VectorSetType.DEBUG_VISUAL);
        }

        /**
         * Create a {@link VectorSetType#FAKE_DOORS} set that sends client-side block packets,
         * making each position in {@code fromPositions} display the block found at the
         * corresponding position in {@code toPositions}. The two arrays must have equal length;
         * entries are matched by index.
         *
         * @param fromPositions positions on the client where fake blocks will appear
         * @param toPositions   world positions whose block data (id, filler, rotation) will be read
         * @return a new fake-door {@code VectorSet}
         */
        @Nonnull
        public static VectorSet fakeDoors(@Nonnull Vector3i[] fromPositions, @Nonnull Vector3i[] toPositions) {
            return new VectorSet(fromPositions, toPositions, null, VectorSetType.FAKE_DOORS);
        }
    }

    /**
     * Discriminates the rendering strategy used by a {@link VectorSet}.
     */
    public enum VectorSetType {
        /** Renders colored wireframe cuboids at the registered positions. */
        DEBUG_VISUAL,
        /** Sends client-side {@link ServerSetBlock} packets to replace blocks visually. */
        FAKE_DOORS
    }

    private ClientsideVisualizationHandler() {
    }

    /**
     * Initialize the visualization system and start the background ticker.
     * Creates the {@link ClientsideVisualizerService} if it has not already been created.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    public static synchronized void initialize() {
        if (visualizerService != null) {
            return;
        }
        visualizerService = new ClientsideVisualizerService();
        ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization handler initialized");
    }

    /**
     * Shut down the visualization system.
     * Stops the background ticker, clears all registered managers, and releases all resources.
     * After this call, {@link #registerManager} and visualization rendering are no-ops until
     * {@link #initialize()} is called again.
     */
    public static synchronized void shutdown() {
        if (visualizerService != null) {
            visualizerService.shutdown();
            visualizerService = null;
            ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization handler shut down");
        }
    }

    /**
     * Register a {@link VisualizationManager} for automatic ticker-driven refresh.
     * Once registered, all visualizations in the manager will be periodically re-sent to
     * each player without requiring explicit calls from the owning system.
     *
     * <p>Logs a warning and returns without registering if the system has not been initialized.
     *
     * @param manager the manager to register
     */
    public static synchronized void registerManager(@Nonnull VisualizationManager manager) {
        ClientsideVisualizerService service = visualizerService;
        if (service == null) {
            ClientsideVisualsPlugin.LOGGER.at(Level.WARNING).log("[Clientside] Cannot register manager - system not initialized");
            return;
        }
        service.registerManager(manager);
    }

    /**
     * Unregister a {@link VisualizationManager} from automatic ticker-driven refresh.
     * This is optional; all managers are cleared automatically on {@link #shutdown()}.
     * Call this only if you need to stop a system's visualizations before plugin shutdown.
     *
     * @param manager the manager to unregister
     */
    public static synchronized void unregisterManager(@Nonnull VisualizationManager manager) {
        ClientsideVisualizerService service = visualizerService;
        if (service != null) {
            service.unregisterManager(manager);
        }
    }

    /**
     * Revert fake-door visualizations for a player by re-sending each position's real block data.
     * Equivalent to calling {@link #sendFakeDoors} with identical from- and to-position arrays,
     * which causes the client to overwrite each fake block with the actual world block.
     *
     * <p>Called internally by {@link VisualizationManager} during clear and disable flows.
     * Returns immediately if {@code positions} is empty or the player ref is invalid.
     *
     * @param playerRef the player whose fake blocks should be reverted
     * @param positions the positions to revert
     */
    static void revertFakeDoors(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] positions) {
        if (positions.length == 0) {
            return;
        }
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        sendFakeDoors(playerRef, positions, positions);
    }

    /**
     * Apply a collection of {@link VectorSet}s to a player in a batched manner.
     * Debug visuals with the same color are merged into a single render call to minimize
     * {@link com.hypixel.hytale.protocol.packets.player.DisplayDebug} packet volume.
     * Fake-door sets are dispatched individually via {@link #sendFakeDoors}.
     *
     * <p>Returns immediately if the visualization service has not been initialized.
     * Called internally by {@link ClientsideVisualizerService} on each ticker cycle.
     *
     * @param playerRef the target player
     * @param playerId  the player's UUID, forwarded to the visualizer service for throttle/cache keying
     * @param sets      the vector sets to apply
     */
    static void applyVectorSets(@Nonnull PlayerRef playerRef, @Nonnull UUID playerId, 
                                @Nonnull Iterable<VectorSet> sets) {
        ClientsideVisualizerService service = visualizerService;
        if (service == null) {
            return;
        }

        // Group debug visuals by color for batched rendering
        Map<Vector3f, List<Vector3i>> debugVisualsByColor = new HashMap<>();
        
        for (VectorSet set : sets) {
            Vector3i[] positions = set.getPositions();
            if (positions.length == 0) {
                continue;
            }

            switch (set.getType()) {
                case DEBUG_VISUAL -> {
                    Vector3f color = set.getDebugColor();
                    if (color != null) {
                        debugVisualsByColor.computeIfAbsent(color, k -> new ArrayList<>())
                            .addAll(Arrays.asList(positions));
                    }
                }
                case FAKE_DOORS -> {
                    Vector3i[] destinationPositions = set.getDestinationPositions();
                    if (destinationPositions != null && destinationPositions.length > 0) {
                        sendFakeDoors(playerRef, positions, destinationPositions);
                    }
                }
            }
        }
        
        // Send batched debug visuals
        for (Map.Entry<Vector3f, List<Vector3i>> entry : debugVisualsByColor.entrySet()) {
            List<Vector3i> positions = entry.getValue();
            if (!positions.isEmpty()) {
                service.debugVisuals(playerId, positions.toArray(new Vector3i[0]), entry.getKey(), false);
            }
        }
    }

    /**
     * Send client-side {@link ServerSetBlock} packets that make each position in
     * {@code fromPositions} display the block found at the corresponding position in
     * {@code toPositions}. Entries are matched by index; if the arrays differ in length,
     * only {@code min(from, to)} pairs are processed and a warning is logged.
     *
     * <p>When {@code fromPositions} and {@code toPositions} are the same array (or contain
     * identical positions), this effectively reverts fake blocks to their real world state.
     *
     * @param playerRef      the player to send updates to
     * @param fromPositions  client-side positions where fake blocks will be displayed
     * @param toPositions    world positions whose block data (id, filler, rotation) will be read
     */
    private static void sendFakeDoors(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] fromPositions, 
                                      @Nonnull Vector3i[] toPositions) {
        int sentCount = 0;
        int failedCount = 0;
        int minLength = Math.min(fromPositions.length, toPositions.length);
        
        for (int index = 0; index < minLength; index++) {
            Vector3i fromPos = fromPositions[index];
            Vector3i toPos = toPositions[index];
            
            if (fromPos == null || toPos == null) {
                failedCount++;
                continue;
            }
            
            // Get the block at the destination position and send it to the source position
            // At index 0 (primary state), we read the block from toPos and place it at fromPos
            ServerSetBlock packet = readBlockAtPositionAsPacket(toPos, fromPos);
            if (packet != null) {
                playerRef.getPacketHandler().writeNoCache(packet);
                ClientsideVisualsPlugin.LOGGER.at(Level.FINE).log("[Visualization] Sent FakeDoor: from=%s to=%s", fromPos, toPos);
                sentCount++;
            } else {
                failedCount++;
            }
        }
        
        if (minLength != Math.max(fromPositions.length, toPositions.length)) {
            ClientsideVisualsPlugin.LOGGER.at(Level.WARNING).log("[Visualization] FakeDoor position count mismatch: from=%d, to=%d",
                fromPositions.length, toPositions.length);
        }
        
        if (sentCount > 0 || failedCount > 0) {
            ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Visualization] sendFakeDoors complete: sent=%d, failed=%d", sentCount, failedCount);
        }
    }

    /**
     * Read the block state at {@code sourcePos} from the first loaded world chunk that contains
     * it, and package that state as a {@link ServerSetBlock} packet targeting {@code destPos}.
     * The packet carries the block id, filler value, and rotation index of the source block.
     *
     * @param sourcePos the world position to read block data from
     * @param destPos   the world position the packet will target on the client
     * @return a ready-to-send packet, or {@code null} if no loaded chunk contains
     *         {@code sourcePos}
     */
    @Nullable
    private static ServerSetBlock readBlockAtPositionAsPacket(@Nonnull Vector3i sourcePos, @Nonnull Vector3i destPos) {
        for (World world : Universe.get().getWorlds().values()) {
            WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(sourcePos.x, sourcePos.z));
            if (chunk == null) {
                continue;
            }

            int blockId = world.getBlock(sourcePos.x, sourcePos.y, sourcePos.z);
            short filler = (short) chunk.getFiller(sourcePos.x, sourcePos.y, sourcePos.z);
            byte rotation = (byte) chunk.getRotationIndex(sourcePos.x, sourcePos.y, sourcePos.z);
            return new ServerSetBlock(destPos.x, destPos.y, destPos.z, blockId, filler, rotation);
        }

        return null;
    }
}
