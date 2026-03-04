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
 * Central clientside visualization handler.
 *
 * This class owns the visualization system lifecycle and provides low-level
 * packet delivery for client-side visualizations. Server-side systems should
 * use VisualizationManager to manage their own visualization state.
 */
public final class ClientsideVisualizationHandler {

    public static final Vector3f GREEN = new Vector3f(0.0F, 1.0F, 0.0F);
    public static final Vector3f ORANGE = new Vector3f(1.0F, 0.5F, 0.0F);
    public static final Vector3f RED = new Vector3f(1.0F, 0.0F, 0.0F);

    @Nullable
    private static volatile ClientsideVisualizerService visualizerService;

    /**
     * Represents a set of positions with associated rendering metadata.
     */
    public static final class VectorSet {
        @Nonnull
        private final Vector3i[] positions;
        @Nullable
        private final Vector3i[] destinationPositions;
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
         * Get the positions to render or apply fake blocks to.
         * 
         * @return Array of block positions
         */
        @Nonnull
        public Vector3i[] getPositions() {
            return positions;
        }

        /**
         * Get the destination positions for fake doors.
         * For fake doors, these are the positions to read block data from.
         * Null for debug visuals.
         * 
         * @return Array of destination positions, or null
         */
        @Nullable
        public Vector3i[] getDestinationPositions() {
            return destinationPositions;
        }

        /**
         * Get the debug color for visualizations.
         * Only used for debug visuals, null for fake doors.
         * 
         * @return RGB color (0.0-1.0), or null
         */
        @Nullable
        public Vector3f getDebugColor() {
            return debugColor;
        }

        /**
         * Get the type of this vector set.
         * 
         * @return The vector set type
         */
        @Nonnull
        public VectorSetType getType() {
            return type;
        }

        /**
         * Creates a debug visual vector set.
         * Renders colored cuboids at the specified positions.
         * 
         * @param positions Block positions to visualize
         * @param color RGB color (0.0-1.0)
         * @return A new debug visual vector set
         */
        @Nonnull
        public static VectorSet debugVisual(@Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
            return new VectorSet(positions, null, color, VectorSetType.DEBUG_VISUAL);
        }

        /**
         * Creates a fake door vector set.
         * Sends fake block updates to make blocks from toPositions appear at fromPositions.
         * 
         * @param fromPositions Positions where fake blocks will appear
         * @param toPositions Positions to read actual block data from
         * @return A new fake door vector set
         */
        @Nonnull
        public static VectorSet fakeDoors(@Nonnull Vector3i[] fromPositions, @Nonnull Vector3i[] toPositions) {
            return new VectorSet(fromPositions, toPositions, null, VectorSetType.FAKE_DOORS);
        }
    }

    /**
     * Enum indicating the type of vector set.
     * DEBUG_VISUAL - Colored cuboid visualizations
     * FAKE_DOORS - Client-side fake block replacements
     */
    public enum VectorSetType {
        DEBUG_VISUAL,
        FAKE_DOORS
    }

    private ClientsideVisualizationHandler() {
    }

    /**
     * Initialize the visualization system.
     * Creates the visualizer service and starts the ticker.
     * Idempotent - safe to call multiple times.
     */
    public static synchronized void initialize() {
        if (visualizerService != null) {
            return;
        }
        visualizerService = new ClientsideVisualizerService();
        ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization handler initialized");
    }

    /**
     * Shutdown the visualization system.
     * Stops the ticker, clears all managers, and releases resources.
     */
    public static synchronized void shutdown() {
        if (visualizerService != null) {
            visualizerService.shutdown();
            visualizerService = null;
            ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization handler shut down");
        }
    }

    /**
     * Register a VisualizationManager to be automatically maintained by the visualization ticker.
     * Once registered, all visualizations in this manager will be periodically refreshed
     * to keep them on-screen without needing explicit enable() calls.
     *
     * @param manager The manager to register
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
     * Unregister a VisualizationManager from automatic ticker updates.
     * Optional - the system automatically cleans up all managers on server shutdown.
     * Only needed if you want to stop visualizations before plugin shutdown.
     *
     * @param manager The manager to unregister
     */
    public static synchronized void unregisterManager(@Nonnull VisualizationManager manager) {
        ClientsideVisualizerService service = visualizerService;
        if (service != null) {
            service.unregisterManager(manager);
        }
    }

    /**
     * Revert fake-door visualizations by restoring blocks at the given positions.
     * Internal use by VisualizationManager during clear/disable flows.
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

    // ============================================================================
    // INTERNAL HELPER METHODS (used by VisualizationManager)
    // ============================================================================

    /**
     * Apply multiple vector sets to a player in a batched manner.
     * Batches all debug visuals together for efficient rendering.
     * Internal use by VisualizationManager.
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
     * Send fake block updates to a player's client.
     * Reads blocks from toPositions and places them at fromPositions on the client.
     * When fromPositions equals toPositions, this effectively reverts fake blocks to their real state.
     * 
     * @param playerRef The player to send updates to
     * @param fromPositions Positions where fake blocks will appear
     * @param toPositions Positions to read actual block data from
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
     * Create a ServerSetBlock packet by reading the block at a source position
     * and preparing it to be placed at a destination position.
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
