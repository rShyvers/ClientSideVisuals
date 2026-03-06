package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlock;
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
 * thread. Internal helpers ({@link #applyVectorSets}, {@link #revertPositions}) are called from
 * the ticker thread owned by {@link ClientsideVisualizerService}.
 */
public final class ClientsideVisualizationHandler {

    public static final Vector3f GREEN  = new Vector3f(0.0F, 1.0F, 0.0F);
    public static final Vector3f ORANGE = new Vector3f(1.0F, 0.5F, 0.0F);
    public static final Vector3f RED    = new Vector3f(1.0F, 0.0F, 0.0F);

    @Nullable
    private static volatile ClientsideVisualizerService visualizerService;

    // -------------------------------------------------------------------------
    // VectorSet — sealed interface, one record per variant
    // -------------------------------------------------------------------------

    /**
     * A set of block positions paired with the metadata needed to render or apply them.
     * Each variant carries exactly the data it requires — no nullable sentinel fields.
     *
     * <ul>
     *   <li>{@link DebugVisual} — colored wireframe cuboids</li>
     *   <li>{@link Replace}     — stamp a fixed block ID onto every position</li>
     *   <li>{@link Mirror}      — copy real block data from source positions to target positions</li>
     * </ul>
     */
    public sealed interface VectorSet permits VectorSet.DebugVisual, VectorSet.Replace, VectorSet.Mirror {

        /** Primary positions that this set operates on. Never {@code null}. */
        @Nonnull Vector3i[] getPositions();

        /**
         * Renders colored wireframe cuboids at {@code positions}.
         * Adjacent positions are automatically merged into larger AABBs by the rendering pipeline.
         *
         * @param positions block positions to visualize
         * @param color     RGB color with each component in {@code [0.0, 1.0]}
         */
        record DebugVisual(
                @Nonnull Vector3i[] positions,
                @Nonnull Vector3f color
        ) implements VectorSet {
            @Override
            public Vector3i[] getPositions() { return positions; }
        }

        /**
         * Stamps a single block ID (with optional rotation) onto every position in
         * {@code positions}. No world chunk read is performed; filler is always {@code 0}.
         *
         * @param positions block positions that will display the replacement block
         * @param blockId   the block ID to send to every position
         * @param rotation  rotation byte applied to every packet (use {@code 0} for no rotation)
         */
        record Replace(
                @Nonnull Vector3i[] positions,
                int blockId,
                byte rotation
        ) implements VectorSet {
            @Override
            public Vector3i[] getPositions() { return positions; }
        }

        /**
         * Copies real block data from each position in {@code fromPositions} to the corresponding
         * position in {@code toPositions}. Arrays are matched by index; lengths should be equal.
         *
         * @param fromPositions world positions whose block data (id, filler, rotation) will be read
         * @param toPositions   client-side positions where that block data will be displayed
         */
        record Mirror(
                @Nonnull Vector3i[] fromPositions,
                @Nonnull Vector3i[] toPositions
        ) implements VectorSet {
            @Override
            public Vector3i[] getPositions() { return fromPositions; }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private ClientsideVisualizationHandler() {}

    /**
     * Initialize the visualization system and start the background ticker.
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
     * Logs a warning and returns without registering if the system has not been initialized.
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
     * All managers are cleared automatically on {@link #shutdown()}; call this only if you need
     * to stop a system's visualizations before plugin shutdown.
     *
     * @param manager the manager to unregister
     */
    public static synchronized void unregisterManager(@Nonnull VisualizationManager manager) {
        ClientsideVisualizerService service = visualizerService;
        if (service != null) {
            service.unregisterManager(manager);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Revert client-side block changes at {@code positions} by re-sending the real world block
     * at each position. Returns immediately if {@code positions} is empty or the player is invalid.
     *
     * <p>Called internally by {@link VisualizationManager} during clear and disable flows.
     *
     * @param playerRef the player whose fake blocks should be reverted
     * @param positions the positions to revert
     */
    static void revertPositions(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] positions) {
        if (positions.length == 0 || !playerRef.isValid()) {
            return;
        }
        sendMirror(playerRef, positions, positions);
    }

    /**
     * Apply a collection of {@link VectorSet}s to a player in a batched manner.
     * {@link VectorSet.DebugVisual}s with the same color are merged into a single render call.
     * {@link VectorSet.Replace} and {@link VectorSet.Mirror} sets are dispatched individually.
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

        Map<Vector3f, List<Vector3i>> debugVisualsByColor = new HashMap<>();

        for (VectorSet set : sets) {
            if (set.getPositions().length == 0) {
                continue;
            }

            switch (set) {
                case VectorSet.DebugVisual v ->
                        debugVisualsByColor
                                .computeIfAbsent(v.color(), k -> new ArrayList<>())
                                .addAll(Arrays.asList(v.positions()));

                case VectorSet.Replace r ->
                        sendReplace(playerRef, r.positions(), r.blockId(), r.rotation());

                case VectorSet.Mirror m ->
                        sendMirror(playerRef, m.fromPositions(), m.toPositions());
            }
        }

        for (Map.Entry<Vector3f, List<Vector3i>> entry : debugVisualsByColor.entrySet()) {
            List<Vector3i> positions = entry.getValue();
            if (!positions.isEmpty()) {
                service.debugVisuals(playerId, positions.toArray(new Vector3i[0]), entry.getKey(), false);
            }
        }
    }

    /**
     * Send client-side {@link ServerSetBlock} packets that stamp {@code blockId} (with
     * {@code rotation}) onto every position in {@code positions}. Filler is always {@code 0}.
     *
     * @param playerRef the player to send updates to
     * @param positions the positions that should display the replacement block
     * @param blockId   the block ID to send to every position
     * @param rotation  rotation byte applied to every packet
     */
    private static void sendReplace(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] positions,
                                    int blockId, byte rotation) {
        int sentCount   = 0;
        int failedCount = 0;

        for (Vector3i pos : positions) {
            if (pos == null) {
                failedCount++;
                continue;
            }
            ServerSetBlock packet = new ServerSetBlock(pos.x, pos.y, pos.z, blockId, (short) 0, rotation);
            playerRef.getPacketHandler().writeNoCache(packet);
            ClientsideVisualsPlugin.LOGGER.at(Level.FINE).log(
                    "[Visualization] Sent Replace: pos=%s blockId=%d rotation=%d", pos, blockId, rotation);
            sentCount++;
        }

        ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log(
                "[Visualization] sendReplace complete: sent=%d, failed=%d", sentCount, failedCount);
    }

    /**
     * Send client-side {@link ServerSetBlock} packets that make each position in
     * {@code toPositions} display the block found at the corresponding position in
     * {@code fromPositions}. Entries are matched by index; if the arrays differ in length,
     * only {@code min(from, to)} pairs are processed and a warning is logged.
     *
     * <p>When {@code fromPositions} and {@code toPositions} are the same array (identical
     * positions), this effectively reverts fake blocks to their real world state.
     *
     * @param playerRef    the player to send updates to
     * @param fromPositions world positions whose block data (id, filler, rotation) will be read
     * @param toPositions   client-side positions where that block data will be displayed
     */
    private static void sendMirror(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] fromPositions,
                                   @Nonnull Vector3i[] toPositions) {
        int sentCount   = 0;
        int failedCount = 0;
        int minLength   = Math.min(fromPositions.length, toPositions.length);

        for (int i = 0; i < minLength; i++) {
            Vector3i fromPos = fromPositions[i];
            Vector3i toPos   = toPositions[i];

            if (fromPos == null || toPos == null) {
                failedCount++;
                continue;
            }

            ServerSetBlock packet = readBlockAtPositionAsPacket(fromPos, toPos);
            if (packet != null) {
                playerRef.getPacketHandler().writeNoCache(packet);
                ClientsideVisualsPlugin.LOGGER.at(Level.FINE).log(
                        "[Visualization] Sent Mirror: from=%s to=%s", fromPos, toPos);
                sentCount++;
            } else {
                failedCount++;
            }
        }

        if (minLength != Math.max(fromPositions.length, toPositions.length)) {
            ClientsideVisualsPlugin.LOGGER.at(Level.WARNING).log(
                    "[Visualization] Mirror position count mismatch: from=%d, to=%d",
                    fromPositions.length, toPositions.length);
        }

        ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log(
                "[Visualization] sendMirror complete: sent=%d, failed=%d", sentCount, failedCount);
    }

    /**
     * Read the block state at {@code fromPos} from the first loaded world chunk that contains it,
     * and package that state as a {@link ServerSetBlock} packet targeting {@code toPos}.
     *
     * @param fromPos the world position to read block data from
     * @param toPos   the world position the packet will target on the client
     * @return a ready-to-send packet, or {@code null} if no loaded chunk contains {@code fromPos}
     */
    @Nullable
    private static ServerSetBlock readBlockAtPositionAsPacket(@Nonnull Vector3i fromPos, @Nonnull Vector3i toPos) {
        for (World world : Universe.get().getWorlds().values()) {
            WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(fromPos.x, fromPos.z));
            if (chunk == null) {
                continue;
            }

            int   blockId  = world.getBlock(fromPos.x, fromPos.y, fromPos.z);
            short filler   = (short) chunk.getFiller(fromPos.x, fromPos.y, fromPos.z);
            byte  rotation = (byte)  chunk.getRotationIndex(fromPos.x, fromPos.y, fromPos.z);
            return new ServerSetBlock(toPos.x, toPos.y, toPos.z, blockId, filler, rotation);
        }
        return null;
    }
}