package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlock;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Clientside rendering service with autonomous ticker for persistent visualizations.
 * 
 * This service automatically refreshes visualizations registered through VisualizationManager instances.
 * Systems only need to manipulate the data in their VisualizationManager, and the ticker
 * handles periodic re-rendering to keep visuals on-screen.
 * 
 * PlayerRefs are looked up on-demand from Universe rather than cached, ensuring
 * references remain valid even if players disconnect/reconnect.
 * 
 * Only lightweight render state is cached per-player: matrix buffers and throttle timestamps.
 */
public class ClientsideVisualizerService {

    private static final long RENDER_INTERVAL_MS = 300L;
    private static final long TICKER_INTERVAL_MS = 1000L; // Refresh every 1 second
    private static final float DISPLAY_TIME = 2.0F; // Visuals expire after 2s, but ticker re-sends them
    private static final float SHAPE_OPACITY = 0.1F;
    private static final float EDGE_THICKNESS = 0.08F;

    private final Map<UUID, Long> lastRenderTime = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> matrixBuffers = new ConcurrentHashMap<>();
    
    // Registered visualization managers that the ticker will monitor
    private final List<VisualizationManager> registeredManagers = new ArrayList<>();
    private ScheduledFuture<?> tickerTask = null;
    private volatile boolean running = false;

    private static final Field OPACITY_FIELD;

    static {
        Field f = null;
        try {
            f = DisplayDebug.class.getDeclaredField("opacity");
        } catch (NoSuchFieldException ignored) {
        }
        OPACITY_FIELD = f;
    }

    /**
     * Create a new visualizer service and start the ticker.
     */
    public ClientsideVisualizerService() {
        startTicker();
    }

    /**
     * Register a VisualizationManager to be monitored by the ticker.
     * The ticker will automatically refresh all visualizations in this manager.
     * 
     * @param manager The manager to register
     */
    public synchronized void registerManager(@Nonnull VisualizationManager manager) {
        if (!registeredManagers.contains(manager)) {
            registeredManagers.add(manager);
            ClientsideVisualsPlugin.LOGGER.at(Level.FINER).log("[Clientside] Registered manager: %s", manager.getSystemId());
        }
    }

    /**
     * Unregister a VisualizationManager from the ticker.
     * Optional - all managers are automatically cleared during shutdown.
     * 
     * @param manager The manager to unregister
     */
    public synchronized void unregisterManager(@Nonnull VisualizationManager manager) {
        registeredManagers.remove(manager);
        ClientsideVisualsPlugin.LOGGER.at(Level.FINER).log("[Clientside] Unregistered manager: %s", manager.getSystemId());
    }

    /**
     * Start the background ticker that refreshes visualizations.
     */
    private synchronized void startTicker() {
        if (tickerTask != null) {
            return;
        }
        
        running = true;
        tickerTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
            this::tickAllVisualizations,
            TICKER_INTERVAL_MS,
            TICKER_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization ticker started");
    }

    /**
     * Stop the background ticker.
     */
    private synchronized void stopTicker() {
        running = false;
        if (tickerTask != null) {
            tickerTask.cancel(false);
            tickerTask = null;
            ClientsideVisualsPlugin.LOGGER.at(Level.INFO).log("[Clientside] Visualization ticker stopped");
        }
    }

    /**
     * Ticker method that refreshes all registered visualizations.
     * This runs periodically to keep visuals persistent.
     */
    private void tickAllVisualizations() {
        if (!running) {
            return;
        }

        try {
            List<VisualizationManager> managers;
            synchronized (this) {
                managers = new ArrayList<>(registeredManagers);
            }

            for (VisualizationManager manager : managers) {
                tickManager(manager);
            }
        } catch (Exception e) {
            ClientsideVisualsPlugin.LOGGER.at(Level.WARNING).log("[Clientside] Error in visualization ticker: %s", e.getMessage());
        }
    }

    /**
     * Refresh visualizations for all players in a specific manager.
     */
    private void tickManager(@Nonnull VisualizationManager manager) {
        // Get all players with visualizations in this manager
        Set<UUID> playerIds = manager.getAllPlayerIds();
        
        for (UUID playerId : playerIds) {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            // Get all vector sets for this player
            Map<String, ClientsideVisualizationHandler.VectorSet> sets = manager.getAll(playerId);
            if (sets.isEmpty()) {
                continue;
            }

            // Refresh all visualizations, including fake doors, to keep client state consistent.
            ClientsideVisualizationHandler.applyVectorSets(playerRef, playerId, sets.values());
        }
    }

    /**
     * Clear all debug shapes for a player.
     * Sends a ClearDebugShapes packet to remove all visualizations.
     * 
     * @param playerUUID The player UUID
     */
    public void clearPlayer(@Nonnull UUID playerUUID) {
        PlayerRef playerRef = Universe.get().getPlayer(playerUUID);
        if (playerRef != null && playerRef.isValid()) {
            try {
                playerRef.getPacketHandler().write(new ClearDebugShapes());
            } catch (Exception e) {
                ClientsideVisualsPlugin.LOGGER.at(Level.WARNING).log("[Clientside] Failed to clear visuals: %s", e.getMessage());
            }
        }
    }

    /**
     * Clear cached render state for a player.
     * Removes throttle timestamps and matrix buffers.
     * Called when a player disconnects or becomes invalid.
     * 
     * @param playerUUID The player UUID
     */
    private void clearPlayerCache(@Nonnull UUID playerUUID) {
        lastRenderTime.remove(playerUUID);
        matrixBuffers.remove(playerUUID);
    }

    /**
     * Render debug visualizations for a player with throttling.
     * Uses greedy cuboid algorithm to batch adjacent blocks for efficiency.
     * 
     * @param playerUUID The player UUID
     * @param positions Block positions to visualize
     * @param color RGB color (0.0-1.0)
     */
    public void debugVisuals(@Nonnull UUID playerUUID,
                             @Nonnull Vector3i[] positions,
                             @Nonnull Vector3f color) {
        debugVisuals(playerUUID, positions, color, true);
    }
    
    /**
     * Render debug visualizations for a player.
     * Uses greedy cuboid algorithm to merge adjacent blocks into larger AABBs.
     * 
     * @param playerUUID The player UUID
     * @param positions Block positions to visualize
     * @param color RGB color (0.0-1.0)
     * @param respectThrottle If true, enforces render throttle (300ms between updates)
     */
    public void debugVisuals(@Nonnull UUID playerUUID,
                             @Nonnull Vector3i[] positions,
                             @Nonnull Vector3f color,
                             boolean respectThrottle) {
        PlayerRef playerRef = Universe.get().getPlayer(playerUUID);
        if (playerRef == null || !playerRef.isValid()) {
            clearPlayerCache(playerUUID);
            return;
        }

        if (respectThrottle) {
            Long lastRender = lastRenderTime.get(playerUUID);
            long now = System.currentTimeMillis();
            if (lastRender != null && now - lastRender < RENDER_INTERVAL_MS) {
                return;
            }
            lastRenderTime.put(playerUUID, now);
        }

        if (positions.length == 0) {
            return;
        }

        VoxelGrid grid = new VoxelGrid(positions);
        for (Vector3i pos : positions) {
            if (pos == null || grid.isRendered(pos)) {
                continue;
            }

            AABB region = grid.findMergedRegion(pos);
            if (region != null) {
                renderAABB(playerRef, playerUUID, region, color);
            }
        }
    }

    /**
     * Shutdown the visualizer service.
     * Stops the ticker, clears all caches and registered managers.
     */
    public void shutdown() {
        stopTicker();
        lastRenderTime.clear();
        matrixBuffers.clear();
        synchronized (this) {
            registeredManagers.clear();
        }
    }

    /**
     * Render a single AABB as a debug cuboid for a player.
     * Creates and caches transformation matrices for efficient rendering.
     * 
     * @param playerRef The player reference
     * @param playerUUID The player UUID (for matrix caching)
     * @param aabb The axis-aligned bounding box to render
     * @param color RGB color (0.0-1.0)
     */
    private void renderAABB(@Nonnull PlayerRef playerRef, @Nonnull UUID playerUUID,
                            @Nonnull AABB aabb, @Nonnull Vector3f color) {
        float minX = aabb.minX;
        float minY = aabb.minY;
        float minZ = aabb.minZ;
        float maxX = aabb.maxX + 1.0F;
        float maxY = aabb.maxY + 1.0F;
        float maxZ = aabb.maxZ + 1.0F;

        float centerX = (minX + maxX) * 0.5F;
        float centerY = (minY + maxY) * 0.5F;
        float centerZ = (minZ + maxZ) * 0.5F;

        float lengthX = maxX - minX;
        float lengthY = maxY - minY;
        float lengthZ = maxZ - minZ;

        float x1 = minX;
        float x2 = maxX;
        float y1 = minY;
        float y2 = maxY;
        float z1 = minZ;
        float z2 = maxZ;

        sendCube(playerRef, playerUUID, centerX, y1, z1, lengthX, EDGE_THICKNESS, EDGE_THICKNESS, color, false);
        sendCube(playerRef, playerUUID, centerX, y1, z2, lengthX, EDGE_THICKNESS, EDGE_THICKNESS, color, false);
        sendCube(playerRef, playerUUID, centerX, y2, z1, lengthX, EDGE_THICKNESS, EDGE_THICKNESS, color, false);
        sendCube(playerRef, playerUUID, centerX, y2, z2, lengthX, EDGE_THICKNESS, EDGE_THICKNESS, color, false);

        sendCube(playerRef, playerUUID, x1, centerY, z1, EDGE_THICKNESS, lengthY, EDGE_THICKNESS, color, false);
        sendCube(playerRef, playerUUID, x1, centerY, z2, EDGE_THICKNESS, lengthY, EDGE_THICKNESS, color, false);
        sendCube(playerRef, playerUUID, x2, centerY, z1, EDGE_THICKNESS, lengthY, EDGE_THICKNESS, color, false);
        sendCube(playerRef, playerUUID, x2, centerY, z2, EDGE_THICKNESS, lengthY, EDGE_THICKNESS, color, false);

        sendCube(playerRef, playerUUID, x1, y1, centerZ, EDGE_THICKNESS, EDGE_THICKNESS, lengthZ, color, false);
        sendCube(playerRef, playerUUID, x1, y2, centerZ, EDGE_THICKNESS, EDGE_THICKNESS, lengthZ, color, false);
        sendCube(playerRef, playerUUID, x2, y1, centerZ, EDGE_THICKNESS, EDGE_THICKNESS, lengthZ, color, false);
        sendCube(playerRef, playerUUID, x2, y2, centerZ, EDGE_THICKNESS, EDGE_THICKNESS, lengthZ, color, false);
    }

    private void sendCube(@Nonnull PlayerRef playerRef, @Nonnull UUID playerUUID,
                          float x, float y, float z, float scaleX, float scaleY, float scaleZ,
                          @Nonnull Vector3f color, boolean fade) {
        float[] matrix = matrixBuffers.computeIfAbsent(playerUUID, ignored -> new float[16]);

        matrix[0] = scaleX;
        matrix[5] = scaleY;
        matrix[10] = scaleZ;
        matrix[15] = 1.0F;
        matrix[1] = 0.0F;
        matrix[2] = 0.0F;
        matrix[3] = 0.0F;
        matrix[4] = 0.0F;
        matrix[6] = 0.0F;
        matrix[7] = 0.0F;
        matrix[8] = 0.0F;
        matrix[9] = 0.0F;
        matrix[11] = 0.0F;
        matrix[12] = x;
        matrix[13] = y;
        matrix[14] = z;

        DisplayDebug packet = new DisplayDebug();
        packet.shape = DebugShape.Cube;
        packet.matrix = matrix;
        packet.color = color;
        packet.time = DISPLAY_TIME;
        packet.fade = fade;
        packet.frustumProjection = null;

        if (OPACITY_FIELD != null) {
            try {
                OPACITY_FIELD.setFloat(packet, SHAPE_OPACITY);
            } catch (Exception ignored) {
            }
        }

        playerRef.getPacketHandler().write(packet);
    }

    /**
     * Simple axis-aligned bounding box representation.
     * Used for batching block visualizations into larger cuboids.
     */
    private static final class AABB {
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;

        AABB(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    /**
     * Greedy voxel grid for merging adjacent blocks into larger AABBs.
     * Implements a greedy meshing algorithm to reduce the number of debug shapes sent.
     * Tracks which voxels have been rendered to avoid duplicate rendering.
     */
    private static final class VoxelGrid {
        private final boolean[][][] cells;
        private final boolean[][][] rendered;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;

        VoxelGrid(@Nonnull Vector3i[] positions) {
            List<Vector3i> valid = new ArrayList<>(positions.length);
            int localMinX = Integer.MAX_VALUE;
            int localMinY = Integer.MAX_VALUE;
            int localMinZ = Integer.MAX_VALUE;
            int localMaxX = Integer.MIN_VALUE;
            int localMaxY = Integer.MIN_VALUE;
            int localMaxZ = Integer.MIN_VALUE;

            for (Vector3i pos : positions) {
                if (pos == null) {
                    continue;
                }
                valid.add(pos);
                localMinX = Math.min(localMinX, pos.x);
                localMinY = Math.min(localMinY, pos.y);
                localMinZ = Math.min(localMinZ, pos.z);
                localMaxX = Math.max(localMaxX, pos.x);
                localMaxY = Math.max(localMaxY, pos.y);
                localMaxZ = Math.max(localMaxZ, pos.z);
            }

            if (valid.isEmpty()) {
                this.minX = 0;
                this.minY = 0;
                this.minZ = 0;
                this.sizeX = 0;
                this.sizeY = 0;
                this.sizeZ = 0;
                this.cells = new boolean[0][0][0];
                this.rendered = new boolean[0][0][0];
                return;
            }

            this.minX = localMinX;
            this.minY = localMinY;
            this.minZ = localMinZ;
            this.sizeX = localMaxX - localMinX + 1;
            this.sizeY = localMaxY - localMinY + 1;
            this.sizeZ = localMaxZ - localMinZ + 1;
            this.cells = new boolean[sizeX][sizeY][sizeZ];
            this.rendered = new boolean[sizeX][sizeY][sizeZ];

            for (Vector3i pos : valid) {
                cells[pos.x - minX][pos.y - minY][pos.z - minZ] = true;
            }
        }

        /**
         * Check if a position contains a voxel.
         * 
         * @param pos The position to check
         * @return true if the position contains a voxel
         */
        boolean contains(@Nonnull Vector3i pos) {
            int x = pos.x - minX;
            int y = pos.y - minY;
            int z = pos.z - minZ;
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                return false;
            }
            return cells[x][y][z];
        }

        /**
         * Check if a position has already been rendered.
         * 
         * @param pos The position to check
         * @return true if the position has been rendered
         */
        boolean isRendered(@Nonnull Vector3i pos) {
            int x = pos.x - minX;
            int y = pos.y - minY;
            int z = pos.z - minZ;
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                return false;
            }
            return rendered[x][y][z];
        }

        /**
         * Mark a position as rendered.
         * 
         * @param pos The position to mark
         */
        void markRendered(@Nonnull Vector3i pos) {
            int x = pos.x - minX;
            int y = pos.y - minY;
            int z = pos.z - minZ;
            if (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ) {
                rendered[x][y][z] = true;
            }
        }

        /**
         * Find and merge adjacent voxels starting from a seed position.
         * Uses greedy expansion in all 6 directions to create the largest possible AABB.
         * Marks all voxels in the merged region as rendered.
         * 
         * @param seed The starting position
         * @return The merged AABB, or null if seed is invalid or already rendered
         */
        AABB findMergedRegion(@Nonnull Vector3i seed) {
            if (!contains(seed) || isRendered(seed)) {
                return null;
            }

            AABB region = new AABB(seed.x, seed.y, seed.z, seed.x, seed.y, seed.z);
            expandDirection(region, 1, 0, 0);
            expandDirection(region, -1, 0, 0);
            expandDirection(region, 0, 1, 0);
            expandDirection(region, 0, -1, 0);
            expandDirection(region, 0, 0, 1);
            expandDirection(region, 0, 0, -1);

            for (int x = region.minX; x <= region.maxX; x++) {
                for (int y = region.minY; y <= region.maxY; y++) {
                    for (int z = region.minZ; z <= region.maxZ; z++) {
                        markRendered(new Vector3i(x, y, z));
                    }
                }
            }

            return region;
        }

        /**
         * Expand an AABB in a specific direction as far as possible.
         * Checks if all voxels in the expansion plane are valid and unrendered.
         * 
         * @param region The AABB to expand
         * @param dx Direction X (-1, 0, or 1)
         * @param dy Direction Y (-1, 0, or 1)
         * @param dz Direction Z (-1, 0, or 1)
         */
        private void expandDirection(@Nonnull AABB region, int dx, int dy, int dz) {
            while (true) {
                int testX = (dx > 0) ? region.maxX + 1 : (dx < 0) ? region.minX - 1 : 0;
                int testY = (dy > 0) ? region.maxY + 1 : (dy < 0) ? region.minY - 1 : 0;
                int testZ = (dz > 0) ? region.maxZ + 1 : (dz < 0) ? region.minZ - 1 : 0;

                boolean canExpand = true;
                int startX = (dx != 0) ? testX : region.minX;
                int endX = (dx != 0) ? testX : region.maxX;
                int startY = (dy != 0) ? testY : region.minY;
                int endY = (dy != 0) ? testY : region.maxY;
                int startZ = (dz != 0) ? testZ : region.minZ;
                int endZ = (dz != 0) ? testZ : region.maxZ;

                for (int x = startX; x <= endX && canExpand; x++) {
                    for (int y = startY; y <= endY && canExpand; y++) {
                        for (int z = startZ; z <= endZ && canExpand; z++) {
                            Vector3i pos = new Vector3i(x, y, z);
                            if (!contains(pos) || isRendered(pos)) {
                                canExpand = false;
                            }
                        }
                    }
                }

                if (!canExpand) {
                    break;
                }

                if (dx > 0) {
                    region.maxX++;
                } else if (dx < 0) {
                    region.minX--;
                } else if (dy > 0) {
                    region.maxY++;
                } else if (dy < 0) {
                    region.minY--;
                } else if (dz > 0) {
                    region.maxZ++;
                } else if (dz < 0) {
                    region.minZ--;
                }
            }
        }
    }

    /**
     * Helper method to send fake blocks to a player.
     * Kept for potential future use.
     */
    private void sendFakeBlocks(@Nonnull PlayerRef playerRef, @Nonnull Vector3i[] positions, 
                                @Nonnull String[] blockTypes) {
        int[] resolvedBlockIds = resolveBlockIds(positions.length, blockTypes);
        for (int index = 0; index < positions.length; index++) {
            Vector3i pos = positions[index];
            int blockId = resolvedBlockIds[index];
            if (blockId <= 0) {
                continue;
            }
            playerRef.getPacketHandler().writeNoCache(
                    new ServerSetBlock(pos.x, pos.y, pos.z, blockId, (short) 0, (byte) 0)
            );
        }
    }

    @Nonnull
    /**
     * Resolve string block type names to numeric block IDs.
     * 
     * @param positionCount Number of positions
     * @param blockTypes Array of block type names
     * @return Array of resolved block IDs (0 for invalid names)
     */
    private int[] resolveBlockIds(int positionCount, @Nonnull String[] blockTypes) {
        int[] resolved = new int[positionCount];

        if (blockTypes.length == positionCount) {
            for (int i = 0; i < positionCount; i++) {
                resolved[i] = resolveBlockId(blockTypes[i]);
            }
            return resolved;
        }

        int chosenBlockId = -1;
        for (String blockType : blockTypes) {
            chosenBlockId = resolveBlockId(blockType);
            if (chosenBlockId > 0) {
                break;
            }
        }

        if (chosenBlockId > 0) {
            Arrays.fill(resolved, chosenBlockId);
        }
        return resolved;
    }

    /**
     * Resolve a single block type name to a numeric block ID.
     * 
     * @param blockTypeName The block type name (e.g., "hytale:air")
     * @return The block ID, or 0 if the name is invalid
     */
    private int resolveBlockId(@Nullable String blockTypeName) {
        if (blockTypeName == null || blockTypeName.isBlank()) {
            return -1;
        }
        int blockId = BlockType.getAssetMap().getIndex(blockTypeName);
        return blockId != Integer.MIN_VALUE ? blockId : -1;
    }
}