package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlock;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlocks;
import com.hypixel.hytale.protocol.packets.world.SetBlockCmd;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Central handler and rendering service for client-side visualizations.
 *
 * <p>Owns the visualization lifecycle, provides low-level packet delivery, and keeps visuals
 * alive via a background ticker. Game systems should create a {@link VisualizationManager} and
 * register it via {@link #registerManager}; the ticker handles periodic re-rendering.
 *
 * <p>The static instance is created by {@link #initialize()} and destroyed by {@link #shutdown()}.
 * All public mutating methods guard against a {@code null} instance and are safe to call before
 * initialization or after shutdown.
 *
 * <h2>Thread safety</h2>
 * {@link #initialize()}, {@link #shutdown()}, {@link #registerManager}, and
 * {@link #unregisterManager} are {@code synchronized} on the class. {@link #debugVisuals} is
 * safe to call concurrently for different players; calling it for the <em>same</em> player
 * concurrently is not recommended, as the per-player matrix buffer is reused without further
 * synchronization.
 */
public final class ClientsideVisualizationHandler {
    // -------------------------------------------------------------------------
    // VectorSet — sealed interface
    // -------------------------------------------------------------------------

    /**
     * A set of block positions paired with metadata needed to render or apply them.
     *
     * <ul>
     *   <li>{@link DebugVisual} — colored wireframe cuboids</li>
     *   <li>{@link Replace}     — stamp a fixed block ID onto every position</li>
     *   <li>{@link Mirror}      — copy real block data from source positions to target positions</li>
     * </ul>
     */
    public sealed interface VectorSet permits VectorSet.DebugVisual, VectorSet.Replace, VectorSet.Mirror {

        @Nonnull Vector3i[] getPositions();

        /**
         * Renders colored wireframe cuboids. Adjacent positions are merged into AABBs
         * by the rendering pipeline.
         */
        record DebugVisual(@Nonnull Vector3i[] positions, @Nonnull Vector3f color) implements VectorSet {
            @Override public @Nonnull Vector3i[] getPositions() { return positions; }
        }

        /**
         * Stamps {@code blockId} (with optional {@code rotation}) onto every position.
         * Filler is always {@code 0}.
         */
        record Replace(@Nonnull Vector3i[] positions, int blockId, byte rotation) implements VectorSet {
            @Override public @Nonnull Vector3i[] getPositions() { return positions; }
        }

        /**
         * Copies real block data from each position in {@code fromPos} to the
         * corresponding position in {@code toPos}. Arrays are matched by index.
         */
        record Mirror(@Nonnull Vector3i[] fromPos, @Nonnull Vector3i[] toPos) implements VectorSet {
            @Override public @Nonnull Vector3i[] getPositions() { return fromPos; }
        }
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long  TICKER_INTERVAL_MS = 1000L;
    private static final float DISPLAY_TIME       = 1.5F;
    private static final float SHAPE_OPACITY      = 0.1F;
    private static final float EDGE_THICKNESS     = 0.08F;

    public static final Vector3f GREEN  = new Vector3f(0.0F, 1.0F, 0.0F);
    public static final Vector3f ORANGE = new Vector3f(1.0F, 0.5F, 0.0F);
    public static final Vector3f RED    = new Vector3f(1.0F, 0.0F, 0.0F);

    // Resolved once at class-load; null if the field does not exist.
    private static final Field OPACITY_FIELD;
    static {
        Field f = null;
        try {
            f = DisplayDebug.class.getDeclaredField("opacity");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) { }
        OPACITY_FIELD = f;
    }

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    @Nullable private static volatile ClientsideVisualizationHandler INSTANCE;

    /** Create and start the visualization handler. Idempotent. */
    public static synchronized void initialize() {
        if (INSTANCE == null) {
            INSTANCE = new ClientsideVisualizationHandler();
        }
    }

    /**
     * Shut down the visualization handler and clear all state.
     * Safe to call even if {@link #initialize()} was never called.
     */
    public static synchronized void shutdown() {
        if (INSTANCE != null) {
            INSTANCE.doShutdown();
            INSTANCE = null;
        }
    }

    /** @return the live instance, or {@code null} if not yet initialized or already shut down */
    @Nullable
    public static ClientsideVisualizationHandler get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final List<VisualizationManager> registeredManagers = new ArrayList<>();

    private ScheduledFuture<?> tickerTask = null;
    private volatile boolean running = false;

    private ClientsideVisualizationHandler() {
        startTicker();
    }

    /**
     * Register a {@link VisualizationManager} to be refreshed by the ticker.
     * Registering the same manager more than once is a no-op.
     */
    public static synchronized void registerManager(@Nonnull VisualizationManager manager) {
        if (INSTANCE == null) return;
        if (!INSTANCE.registeredManagers.contains(manager)) {
            INSTANCE.registeredManagers.add(manager);
            ClientsideVisualsPlugin.LOGGER.at(Level.FINER)
                    .log("[Clientside] Registered manager: %s", manager.getSystemId());
        }
    }

    /**
     * Unregister a {@link VisualizationManager} from the ticker.
     * Optional; all managers are cleared automatically on {@link #shutdown()}.
     */
    public static synchronized void unregisterManager(@Nonnull VisualizationManager manager) {
        if (INSTANCE == null) return;
        INSTANCE.registeredManagers.remove(manager);
        ClientsideVisualsPlugin.LOGGER.at(Level.FINER)
                .log("[Clientside] Unregistered manager: %s", manager.getSystemId());
    }

    // -------------------------------------------------------------------------
    // Lifecycle (instance)
    // -------------------------------------------------------------------------

    private synchronized void startTicker() {
        if (tickerTask != null) return;
        running = true;
        tickerTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                this::tickVisuals,
                TICKER_INTERVAL_MS, TICKER_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
        ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization ticker started");
    }

    private synchronized void stopTicker() {
        running = false;
        if (tickerTask != null) {
            tickerTask.cancel(false);
            tickerTask = null;
            ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization ticker stopped");
        }
    }

    private void doShutdown() {
        stopTicker();
        synchronized (this) {
            registeredManagers.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    private void tickVisuals() {
        if (!running) return;
        List<VisualizationManager> managers;
        synchronized (this) {
            managers = new ArrayList<>(registeredManagers);
        }
        for (VisualizationManager manager : managers) {
            try {
                tickManager(manager);
            } catch (Exception e) {
                ClientsideVisualsPlugin.LOGGER.at(Level.WARNING)
                        .log("[Clientside] Error ticking manager %s: %s", manager.getSystemId(), e.getMessage());
            }
        }
    }

    private void tickManager(@Nonnull VisualizationManager manager) {
        for (UUID playerId : manager.getAllPlayerIds()) {
            HytaleServer.SCHEDULED_EXECUTOR.submit(() -> {
                PlayerRef playerRef = Universe.get().getPlayer(playerId);
                if (playerRef == null || !playerRef.isValid()) return;

                Map<String, VectorSet> sets = manager.getAll(playerId);
                if (sets.isEmpty()) return;

                applyVectorSets(playerRef, sets.values());
            });
        }
    }

    /**
     * Apply a collection of {@link VectorSet}s to a player. {@link VectorSet.DebugVisual}s with
     * the same color are batched into a single render call; Replace and Mirror sets are dispatched
     * individually.
     */
    static void applyVectorSets(@Nonnull PlayerRef playerRef, @Nonnull Iterable<VectorSet> sets) {
        for (VectorSet set : sets) {
            if (set.getPositions().length == 0) continue;
            switch (set) {
                case VectorSet.DebugVisual v ->
                    debugVisuals(playerRef, v.positions(), v.color());
                case VectorSet.Replace r ->
                    sendReplace(playerRef, r.positions(), r.blockId(), r.rotation());
                case VectorSet.Mirror m ->
                    sendMirror(playerRef, m.fromPos(), m.toPos());
            }
        }
    }

    /**
     * Render debug wireframe cuboids for a player. Adjacent positions are merged into the
     * largest possible AABBs using a greedy algorithm to minimize packet count.
     *
     * @param playerRef      the target player
     * @param positions       block positions to visualize; {@code null} entries are ignored
     * @param color           RGB color, each component in {@code [0.0, 1.0]}
     */
    private static void debugVisuals(@Nonnull PlayerRef playerRef,
                             @Nonnull Vector3i[] positions,
                             @Nonnull Vector3f color) {
        VoxelGrid grid = new VoxelGrid(positions);
        for (Vector3i pos : positions) {
            if (pos == null || grid.isRendered(pos)) continue;
            AABB region = grid.findMergedRegion(pos);
            if (region != null) {
                renderAABB(playerRef, region, color);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (used by VisualizationManager)
    // -------------------------------------------------------------------------

    /**
     * Revert client-side block changes at {@code positions} by re-sending the real world block
     * at each position. Called by {@link VisualizationManager} during clear and disable flows.
     */
    static void revertPositions(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] positions) {
        if (positions.length == 0 || !playerRef.isValid()) return;
        sendMirror(playerRef, positions, positions);
    }



    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Sends block-replacement packets to a player, stamping every position in {@code positions}
     * with the given {@code blockId} and {@code rotation} (filler is always {@code 0}).
     *
     * <p>Positions are automatically batched by chunk section to minimize packet count:
     * <ul>
     *   <li>A single position in a section is sent as a {@link ServerSetBlock} (packet 140).</li>
     *   <li>Multiple positions in the same section are combined into one
     *       {@link ServerSetBlocks} (packet 141), where each entry's {@code index} is the
     *       flat intra-section index produced by {@link ChunkUtil#indexBlock(int, int, int)}.</li>
     * </ul>
     *
     * <p>Null entries in {@code positions} are silently skipped. No chunk-loaded check is
     * performed; callers are responsible for ensuring the player has the relevant sections
     * loaded before calling this method.
     *
     * @param playerRef the target player to send the packets to
     * @param positions world-space block coordinates to overwrite; may contain null entries
     * @param blockId   the block ID to write at every position
     * @param rotation  the rotation index to apply to every block
     */
    private static void sendReplace(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] positions,
                                    int blockId, byte rotation) {
        Map<Long, List<Vector3i>> bySectionMap = new HashMap<>();

        for (Vector3i pos : positions) {
            if (pos == null) continue;
            int sx = ChunkUtil.chunkCoordinate(pos.x);
            int sy = ChunkUtil.chunkCoordinate(pos.y);
            int sz = ChunkUtil.chunkCoordinate(pos.z);
            // Pack all 3 section coords into a single long key
            long key = ChunkUtil.indexChunk(sx, sz) ^ ((long) sy << 48);
            bySectionMap.computeIfAbsent(key, _ -> new ArrayList<>()).add(pos);
        }

        for (List<Vector3i> group : bySectionMap.values()) {
            Vector3i first = group.getFirst();
            int sx = ChunkUtil.chunkCoordinate(first.x);
            int sy = ChunkUtil.chunkCoordinate(first.y);
            int sz = ChunkUtil.chunkCoordinate(first.z);

            if (group.size() == 1) {
                playerRef.getPacketHandler().writeNoCache(
                        new ServerSetBlock(first.x, first.y, first.z, blockId, (short) 0, rotation));
            } else {
                SetBlockCmd[] cmds = new SetBlockCmd[group.size()];
                for (int i = 0; i < group.size(); i++) {
                    Vector3i pos = group.get(i);
                    // indexBlock masks with & 31 internally, so world coords are fine
                    short index = (short) ChunkUtil.indexBlock(pos.x, pos.y, pos.z);
                    cmds[i] = new SetBlockCmd(index, blockId, (short) 0, rotation);
                }
                playerRef.getPacketHandler().writeNoCache(
                        new ServerSetBlocks(sx, sy, sz, cmds));
            }
        }
    }

    /**
     * Sends block-mirror packets to a player, copying the real block data at each position in
     * {@code fromPos} to the corresponding destination in {@code toPos}.
     * When both arrays are identical, this effectively reverts client-side fake blocks back
     * to their true world state.
     *
     * <p>Block data is always read from the player's current world. If the player has no
     * associated world, or the world cannot be resolved, the method returns immediately
     * without sending any packets.
     *
     * <p>Destinations are batched by chunk section to minimize packet count:
     * <ul>
     *   <li>A single destination in a section is sent as a {@link ServerSetBlock} (packet 140).</li>
     *   <li>Multiple destinations in the same section are combined into one
     *       {@link ServerSetBlocks} (packet 141), preserving the individual {@code blockId},
     *       {@code filler}, and {@code rotation} read from each source position.</li>
     * </ul>
     *
     * <p>If the two arrays differ in length, a warning is logged and only the overlapping
     * prefix (up to {@code min(fromPos.length, toPos.length)}) is processed.
     * Null entries in either array, or positions whose source chunk is not currently loaded,
     * are silently skipped.
     *
     * <p>No chunk-loaded check is performed on the destination; callers are responsible for
     * ensuring the player has the relevant sections loaded before calling this method.
     *
     * @param playerRef     the target player to send the packets to
     * @param fromPos world-space source coordinates to read block data from
     * @param toPos   world-space destination coordinates to write block data to;
     *                      must correspond 1-to-1 with {@code fromPos}
     */
    private static void sendMirror(@Nonnull PlayerRef playerRef,
                                    @Nonnull Vector3i[] fromPos,
                                    @Nonnull Vector3i[] toPos) {
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) return;
        World world = Universe.get().getWorld(worldUuid);
        if (world == null) return;

        int len = Math.min(fromPos.length, toPos.length);
        if (fromPos.length != toPos.length) {
            ClientsideVisualsPlugin.LOGGER.at(Level.WARNING)
                    .log("[Clientside] Mirror length mismatch: from=%d, to=%d",
                            fromPos.length, toPos.length);
        }

        Map<Long, List<ServerSetBlock>> bySectionMap = new HashMap<>();

        for (int i = 0; i < len; i++) {
            Vector3i from = fromPos[i], to = toPos[i];
            if (from == null || to == null) continue;

            WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(from.x, from.z));
            if (chunk == null) continue; // source chunk not loaded

            ServerSetBlock packet = new ServerSetBlock(to.x, to.y, to.z,
                    world.getBlock(from.x, from.y, from.z),
                    (short) chunk.getFiller(from.x, from.y, from.z),
                    (byte)  chunk.getRotationIndex(from.x, from.y, from.z));

            int sx = ChunkUtil.chunkCoordinate(to.x);
            int sy = ChunkUtil.chunkCoordinate(to.y);
            int sz = ChunkUtil.chunkCoordinate(to.z);
            long key = ChunkUtil.indexChunk(sx, sz) ^ ((long) sy << 48);
            bySectionMap.computeIfAbsent(key, _ -> new ArrayList<>()).add(packet);
        }

        for (List<ServerSetBlock> group : bySectionMap.values()) {
            if (group.size() == 1) {
                playerRef.getPacketHandler().writeNoCache(group.getFirst());
            } else {
                ServerSetBlock first = group.getFirst();
                int sx = ChunkUtil.chunkCoordinate(first.x);
                int sy = ChunkUtil.chunkCoordinate(first.y);
                int sz = ChunkUtil.chunkCoordinate(first.z);

                SetBlockCmd[] cmds = new SetBlockCmd[group.size()];
                for (int i = 0; i < group.size(); i++) {
                    ServerSetBlock p = group.get(i);
                    cmds[i] = new SetBlockCmd((short) ChunkUtil.indexBlock(p.x, p.y, p.z),
                            p.blockId, p.filler, p.rotation);
                }
                playerRef.getPacketHandler().writeNoCache(
                        new ServerSetBlocks(sx, sy, sz, cmds));
            }
        }
    }

    /**
     * Decompose an {@link AABB} into 12 edge cuboids and render each as a thin wireframe segment.
     */
    private static void renderAABB(@Nonnull PlayerRef playerRef, @Nonnull AABB aabb, @Nonnull Vector3f color) {
        float x1 = aabb.minX,      y1 = aabb.minY,      z1 = aabb.minZ;
        float x2 = aabb.maxX + 1f, y2 = aabb.maxY + 1f, z2 = aabb.maxZ + 1f;
        float cx = (x1 + x2) * 0.5f, cy = (y1 + y2) * 0.5f, cz = (z1 + z2) * 0.5f;
        float lx = x2 - x1, ly = y2 - y1, lz = z2 - z1;
        float t  = EDGE_THICKNESS;

        // Four edges along X
        sendCube(playerRef, cx, y1, z1, lx, t,  t,  color);
        sendCube(playerRef, cx, y1, z2, lx, t,  t,  color);
        sendCube(playerRef, cx, y2, z1, lx, t,  t,  color);
        sendCube(playerRef, cx, y2, z2, lx, t,  t,  color);
        // Four edges along Y
        sendCube(playerRef, x1, cy, z1, t,  ly, t,  color);
        sendCube(playerRef, x1, cy, z2, t,  ly, t,  color);
        sendCube(playerRef, x2, cy, z1, t,  ly, t,  color);
        sendCube(playerRef, x2, cy, z2, t,  ly, t,  color);
        // Four edges along Z
        sendCube(playerRef, x1, y1, cz, t,  t,  lz, color);
        sendCube(playerRef, x1, y2, cz, t,  t,  lz, color);
        sendCube(playerRef, x2, y1, cz, t,  t,  lz, color);
        sendCube(playerRef, x2, y2, cz, t,  t,  lz, color);
    }

    /**
     * Build a {@link DisplayDebug} packet for a single axis-aligned cuboid and dispatch it.
     * The 4×4 transformation matrix is assembled in a per-player buffer, allocated once and
     * reused to avoid repeated allocation.
     */
    private static void sendCube(@Nonnull PlayerRef playerRef,
                          float x, float y, float z,
                          float scaleX, float scaleY, float scaleZ,
                          @Nonnull Vector3f color) {
        float[] m = new float[16];
        // Column-major 4×4: scale on diagonal, translation in column 3
        m[0]  = scaleX; m[1]  = 0; m[2]  = 0; m[3]  = 0;
        m[4]  = 0; m[5]  = scaleY; m[6]  = 0; m[7]  = 0;
        m[8]  = 0; m[9]  = 0; m[10] = scaleZ; m[11] = 0;
        m[12] = x; m[13] = y; m[14] = z;       m[15] = 1f;

        DisplayDebug packet = new DisplayDebug();
        packet.shape             = DebugShape.Cube;
        packet.matrix            = m;
        packet.color             = color;
        packet.time              = DISPLAY_TIME;
        packet.fade              = false;
        packet.frustumProjection = null;

        if (OPACITY_FIELD != null) {
            try { OPACITY_FIELD.setFloat(packet, SHAPE_OPACITY); } catch (Exception ignored) { }
        }

        playerRef.getPacketHandler().write(packet);
    }

    // -------------------------------------------------------------------------
    // AABB
    // -------------------------------------------------------------------------

    /** Axis-aligned bounding box used for batching block visualizations into larger cuboids. */
    private static final class AABB {
        int minX, minY, minZ, maxX, maxY, maxZ;

        AABB(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    // -------------------------------------------------------------------------
    // VoxelGrid — greedy meshing
    // -------------------------------------------------------------------------

    /**
     * Greedy voxel grid that merges adjacent blocks into the largest possible AABBs,
     * minimizing the number of debug shapes sent to the client.
     */
    private static final class VoxelGrid {
        private final boolean[][][] cells;
        private final boolean[][][] rendered;
        private final int minX, minY, minZ;
        private final int sizeX, sizeY, sizeZ;

        VoxelGrid(@Nonnull Vector3i[] positions) {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            List<Vector3i> valid = new ArrayList<>(positions.length);

            for (Vector3i p : positions) {
                if (p == null) continue;
                valid.add(p);
                x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); z0 = Math.min(z0, p.z);
                x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); z1 = Math.max(z1, p.z);
            }

            if (valid.isEmpty()) {
                minX = minY = minZ = 0;
                sizeX = sizeY = sizeZ = 0;
                cells = rendered = new boolean[0][0][0];
                return;
            }

            minX = x0; minY = y0; minZ = z0;
            sizeX = x1 - x0 + 1; sizeY = y1 - y0 + 1; sizeZ = z1 - z0 + 1;
            cells    = new boolean[sizeX][sizeY][sizeZ];
            rendered = new boolean[sizeX][sizeY][sizeZ];

            for (Vector3i p : valid) {
                cells[p.x - minX][p.y - minY][p.z - minZ] = true;
            }
        }

        boolean contains(@Nonnull Vector3i p) {
            int x = p.x - minX, y = p.y - minY, z = p.z - minZ;
            return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ
                    && cells[x][y][z];
        }

        boolean isRendered(@Nonnull Vector3i p) {
            int x = p.x - minX, y = p.y - minY, z = p.z - minZ;
            return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ
                    && rendered[x][y][z];
        }

        void markRendered(@Nonnull Vector3i p) {
            int x = p.x - minX, y = p.y - minY, z = p.z - minZ;
            if (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ) {
                rendered[x][y][z] = true;
            }
        }

        /**
         * Greedily expand from {@code seed} in all six directions to find the largest AABB
         * that contains only occupied, unrendered voxels. All consumed voxels are immediately
         * marked rendered. Returns {@code null} if {@code seed} is unoccupied or already rendered.
         */
        @Nullable
        AABB findMergedRegion(@Nonnull Vector3i seed) {
            if (!contains(seed) || isRendered(seed)) return null;

            AABB r = new AABB(seed.x, seed.y, seed.z, seed.x, seed.y, seed.z);
            expand(r,  1, 0, 0); expand(r, -1, 0, 0);
            expand(r,  0, 1, 0); expand(r,  0,-1, 0);
            expand(r,  0, 0, 1); expand(r,  0, 0,-1);

            for (int x = r.minX; x <= r.maxX; x++)
                for (int y = r.minY; y <= r.maxY; y++)
                    for (int z = r.minZ; z <= r.maxZ; z++)
                        markRendered(new Vector3i(x, y, z));

            return r;
        }

        /**
         * Attempt to extend {@code r} one step at a time along (dx, dy, dz).
         * Expansion halts as soon as any voxel on the new face is missing or already rendered.
         */
        private void expand(@Nonnull AABB r, int dx, int dy, int dz) {
            while (true) {
                int tx = dx > 0 ? r.maxX + 1 : dx < 0 ? r.minX - 1 : 0;
                int ty = dy > 0 ? r.maxY + 1 : dy < 0 ? r.minY - 1 : 0;
                int tz = dz > 0 ? r.maxZ + 1 : dz < 0 ? r.minZ - 1 : 0;

                int sx = dx != 0 ? tx : r.minX, ex = dx != 0 ? tx : r.maxX;
                int sy = dy != 0 ? ty : r.minY, ey = dy != 0 ? ty : r.maxY;
                int sz = dz != 0 ? tz : r.minZ, ez = dz != 0 ? tz : r.maxZ;

                for (int x = sx; x <= ex; x++)
                    for (int y = sy; y <= ey; y++)
                        for (int z = sz; z <= ez; z++)
                            if (!contains(new Vector3i(x, y, z)) || isRendered(new Vector3i(x, y, z))) return;

                if      (dx > 0) r.maxX++; else if (dx < 0) r.minX--;
                else if (dy > 0) r.maxY++; else if (dy < 0) r.minY--;
                else if (dz > 0) r.maxZ++; else             r.minZ--;
            }
        }
    }
}