package com.landofsharks.clientsidevisuals;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages clientside visualizations for a specific system or plugin.
 * 
 * Each plugin/system should create its own VisualizationManager instance
 * to isolate their visualization state and prevent conflicts with other systems.
 * 
 * The manager automatically registers itself with the visualization system upon creation
 * and begins ticker-based persistence immediately. Manual unregistration is optional -
 * the system auto-cleans all managers when the server shuts down.
 * 
 * Supports:
 * - Single visualization mode (set/clear) - replaces previous visualizations
 * - Multi visualization mode (add/clear) - composites with existing visualizations
 * - Automatic persistence via ticker refresh
 */
public class VisualizationManager {
    
    @Nonnull
    private final String systemId;
    
    // Internal storage: Map<PlayerUUID, Map<SetId, VectorSet>>
    @Nonnull
    private final Map<UUID, Map<String, ClientsideVisualizationHandler.VectorSet>> playerVectorSets = new ConcurrentHashMap<>();

    /**
     * Create a new VisualizationManager with a unique system ID.
     * Automatically registers with the visualization system for ticker-based persistence.
     * 
     * @param systemId A unique identifier for this system (e.g., "greenhouse_main", "zone_system")
     */
    public VisualizationManager(@Nonnull String systemId) {
        this.systemId = Objects.requireNonNull(systemId, "System ID cannot be null");
        ClientsideVisualizationHandler.registerManager(this);
    }

    /**
     * Create a new VisualizationManager with an auto-generated system ID.
     * Automatically registers with the visualization system for ticker-based persistence.
     */
    public VisualizationManager() {
        this.systemId = "system_" + System.identityHashCode(this);
        ClientsideVisualizationHandler.registerManager(this);
    }

    /**
     * Get the unique system ID for this manager.
     * Used for logging and debugging.
     * 
     * @return The system ID
     */
    @Nonnull
    public String getSystemId() {
        return systemId;
    }

    // ============================================================================
    // SET - Single visualization (clears others)
    // ============================================================================

    /**
     * Set a debug visual visualization for a player.
     * Clears all other visualizations for this player within this system.
     * 
     * @param playerId The player UUID
     * @param positions The block positions to visualize
     * @param color The color to render
     */
    public void setDebugVisuals(@Nonnull UUID playerId, @Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");
        
        ClientsideVisualizationHandler.VectorSet set = ClientsideVisualizationHandler.VectorSet.debugVisual(positions, color);
        clearAndRegisterVectorSet(playerId, "default", set);
    }

    /**
     * Set a debug visual visualization for a player using a bounding box.
     * Clears all other visualizations for this player within this system.
     * Fills all blocks between the min and max coordinates (inclusive).
     * 
     * @param playerId The player UUID
     * @param min The minimum corner of the bounding box
     * @param max The maximum corner of the bounding box
     * @param color The color to render
     */
    public void setDebugVisuals(@Nonnull UUID playerId, @Nonnull Vector3i min, @Nonnull Vector3i max, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");
        
        Vector3i[] positions = generatePositionsInBoundingBox(min, max);
        setDebugVisuals(playerId, positions, color);
    }

    /**
     * Set a fake doors visualization for a player.
     * Clears all other visualizations for this player within this system.
     * Replaces blocks at the given positions with air (blockstate 0).
     * 
     * @param playerId The player UUID
     * @param positions The block positions to replace with air
     */
    public void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i[] positions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");

        setFakeDoors(playerId, positions, positions);
    }

    /**
     * Set a fake doors visualization for a player.
     * Clears all other visualizations for this player within this system.
     *
     * @param playerId The player UUID
     * @param fromPositions The positions to apply fake block updates to
     * @param toPositions The positions to copy block states from
     */
    private void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i[] fromPositions, @Nonnull Vector3i[] toPositions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(fromPositions, "From positions cannot be null");
        Objects.requireNonNull(toPositions, "To positions cannot be null");

        ClientsideVisualizationHandler.VectorSet set = ClientsideVisualizationHandler.VectorSet.fakeDoors(fromPositions, toPositions);
        clearAndRegisterVectorSet(playerId, "default", set);
    }

    /**
     * Set a fake doors visualization for a player using a bounding box.
     * Clears all other visualizations for this player within this system.
     * Replaces all blocks in the region with air (blockstate 0).
     * 
     * @param playerId The player UUID
     * @param min The minimum corner of the bounding box
     * @param max The maximum corner of the bounding box
     */
    public void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i min, @Nonnull Vector3i max) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        
        Vector3i[] positions = generatePositionsInBoundingBox(min, max);
        setFakeDoors(playerId, positions);
    }

    /**
     * Clear a specific visualization by set ID.
     * 
     * @param playerId The player UUID
     * @param setId The identifier of the set to remove
     * @return The removed vector set, or null if it didn't exist
     */
    @Nullable
    public ClientsideVisualizationHandler.VectorSet clear(@Nonnull UUID playerId, @Nonnull String setId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        
        Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
        if (sets == null) {
            return null;
        }
        
        ClientsideVisualizationHandler.VectorSet removed = sets.remove(setId);
        revertFakeDoorSet(playerId, removed);
        if (sets.isEmpty()) {
            playerVectorSets.remove(playerId);
        }
        return removed;
    }

    // ============================================================================
    // SETMULTI - Multiple visualizations (does not clear)
    // ============================================================================

    /**
     * Add a debug visual visualization for a player.
     * Does not clear other visualizations - allows composite rendering.
     * 
     * @param playerId The player UUID
     * @param setId A unique identifier for this set (e.g., "zone_boundary", "pending_blocks")
     * @param positions The block positions to visualize
     * @param color The color to render
     */
    public void addDebugVisuals(@Nonnull UUID playerId, @Nonnull String setId, 
                               @Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");
        
        ClientsideVisualizationHandler.VectorSet set = ClientsideVisualizationHandler.VectorSet.debugVisual(positions, color);
        registerVectorSet(playerId, setId, set);
    }

    /**
     * Add a debug visual visualization for a player using a bounding box.
     * Does not clear other visualizations - allows composite rendering.
     * Fills all blocks between the min and max coordinates (inclusive).
     * 
     * @param playerId The player UUID
     * @param setId A unique identifier for this set (e.g., "zone_boundary", "pending_blocks")
     * @param min The minimum corner of the bounding box
     * @param max The maximum corner of the bounding box
     * @param color The color to render
     */
    public void addDebugVisuals(@Nonnull UUID playerId, @Nonnull String setId, 
                               @Nonnull Vector3i min, @Nonnull Vector3i max, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");
        
        Vector3i[] positions = generatePositionsInBoundingBox(min, max);
        addDebugVisuals(playerId, setId, positions, color);
    }

    /**
     * Add a fake doors visualization for a player.
     * Does not clear other visualizations - allows composite rendering.
     * Replaces blocks at the given positions with air (blockstate 0).
     * 
     * @param playerId The player UUID
     * @param setId A unique identifier for this set (e.g., "zone_boundary", "pending_blocks")
     * @param positions The block positions to replace with air
     */
    public void addFakeDoors(@Nonnull UUID playerId, @Nonnull String setId, @Nonnull Vector3i[] positions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");

        addFakeDoors(playerId, setId, positions, positions);
    }

    /**
     * Add a fake doors visualization for a player.
     * Does not clear other visualizations - allows composite rendering.
     *
     * @param playerId The player UUID
     * @param setId A unique identifier for this set (e.g., "zone_boundary", "pending_blocks")
     * @param fromPositions The positions to apply fake block updates to
     * @param toPositions The positions to copy block states from
     */
    public void addFakeDoors(@Nonnull UUID playerId, @Nonnull String setId,
                             @Nonnull Vector3i[] fromPositions, @Nonnull Vector3i[] toPositions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(fromPositions, "From positions cannot be null");
        Objects.requireNonNull(toPositions, "To positions cannot be null");

        ClientsideVisualizationHandler.VectorSet set = ClientsideVisualizationHandler.VectorSet.fakeDoors(fromPositions, toPositions);
        registerVectorSet(playerId, setId, set);
    }

    /**
     * Add a fake doors visualization for a player using a bounding box.
     * Does not clear other visualizations - allows composite rendering.
     * Replaces all blocks in the region with air (blockstate 0).
     * 
     * @param playerId The player UUID
     * @param setId A unique identifier for this set (e.g., "zone_boundary", "pending_blocks")
     * @param min The minimum corner of the bounding box
     * @param max The maximum corner of the bounding box
     */
    public void addFakeDoors(@Nonnull UUID playerId, @Nonnull String setId, @Nonnull Vector3i min, @Nonnull Vector3i max) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        
        Vector3i[] positions = generatePositionsInBoundingBox(min, max);
        addFakeDoors(playerId, setId, positions);
    }

    /**
     * Get all visualizations registered for a player in this system.
     * 
     * @param playerId The player UUID
     * @return A map of set ID to vector set (empty map if player has no sets)
     */
    @Nonnull
    Map<String, ClientsideVisualizationHandler.VectorSet> getAll(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        
        Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
        return sets != null ? new HashMap<>(sets) : new HashMap<>();
    }

    /**
     * Clear all visualizations for a player in this system.
     * 
     * @param playerId The player UUID
     */
    public void clear(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Map<String, ClientsideVisualizationHandler.VectorSet> removedSets = playerVectorSets.remove(playerId);
        revertFakeDoorSets(playerId, removedSets);
    }

    // ============================================================================
    // ENABLE/DISABLE
    // ============================================================================

    /**
     * Enable all registered visualizations for a player.
     * This will send all registered visualizations to the player.
     * Batches all debug visuals together for efficient rendering.
     * 
     * @param playerId The player UUID
     */
    void enable(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        
        Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
        if (sets == null || sets.isEmpty()) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        // Batch all vector sets together for efficient rendering
        ClientsideVisualizationHandler.applyVectorSets(playerRef, playerId, sets.values());
    }

    /**
     * Disable all visualizations for a player in this system.
     * This clears all registered visualizations.
     * 
     * @param playerId The player UUID
     */
    public void disable(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        clear(playerId);
    }

    /**
     * Get all player UUIDs that have visualizations registered in this system.
     * Used by the ticker to know which players need visualization updates.
     * 
     * @return Set of player UUIDs
     */
    @Nonnull
    Set<UUID> getAllPlayerIds() {
        return new HashSet<>(playerVectorSets.keySet());
    }

    // ============================================================================
    // INTERNAL METHODS
    // ============================================================================

    /**
     * Register a vector set for a player with the given identifier.
     * Multiple sets can be registered per player, allowing for composite rendering.
     * If a set with the same ID already exists, it will be replaced.
     * 
     * @param playerId The player UUID
     * @param setId A unique identifier for this set
     * @param vectorSet The vector set to register
     */
    private void registerVectorSet(@Nonnull UUID playerId, @Nonnull String setId, 
                                   @Nonnull ClientsideVisualizationHandler.VectorSet vectorSet) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(vectorSet, "Vector set cannot be null");
        
        playerVectorSets.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(setId, vectorSet);
    }

    /**
     * Clear all vector sets for a player and register a new one.
     * 
     * @param playerId The player UUID
     * @param setId A unique identifier for the new set
     * @param vectorSet The vector set to register
     */
    private void clearAndRegisterVectorSet(@Nonnull UUID playerId, @Nonnull String setId, 
                                           @Nonnull ClientsideVisualizationHandler.VectorSet vectorSet) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(vectorSet, "Vector set cannot be null");
        
        clear(playerId);
        registerVectorSet(playerId, setId, vectorSet);
    }

    /**
     * Revert multiple fake door sets for a player.
     * Sends packets to restore actual blocks at all fake door positions.
     * 
     * @param playerId The player UUID
     * @param sets Map of vector sets to revert (only FAKE_DOORS type are reverted)
     */
    private void revertFakeDoorSets(@Nonnull UUID playerId,
                                    @Nullable Map<String, ClientsideVisualizationHandler.VectorSet> sets) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        if (sets == null || sets.isEmpty()) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        for (ClientsideVisualizationHandler.VectorSet set : sets.values()) {
            if (set != null && set.getType() == ClientsideVisualizationHandler.VectorSetType.FAKE_DOORS) {
                ClientsideVisualizationHandler.revertFakeDoors(playerRef, set.getPositions());
            }
        }
    }

    /**
     * Revert a single fake door set for a player.
     * Sends packets to restore actual blocks at fake door positions.
     * 
     * @param playerId The player UUID
     * @param set The vector set to revert (must be FAKE_DOORS type)
     */
    private void revertFakeDoorSet(@Nonnull UUID playerId,
                                   @Nullable ClientsideVisualizationHandler.VectorSet set) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        if (set == null || set.getType() != ClientsideVisualizationHandler.VectorSetType.FAKE_DOORS) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        ClientsideVisualizationHandler.revertFakeDoors(playerRef, set.getPositions());
    }

    /**
     * Generate all block positions within a bounding box (inclusive).
     * 
     * @param min The minimum corner of the bounding box
     * @param max The maximum corner of the bounding box
     * @return Array of all Vector3i positions within the bounding box
     */
    private Vector3i[] generatePositionsInBoundingBox(@Nonnull Vector3i min, @Nonnull Vector3i max) {
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        
        // Normalize min and max to ensure they are in the correct order
        int minX = Math.min(min.x, max.x);
        int maxX = Math.max(min.x, max.x);
        int minY = Math.min(min.y, max.y);
        int maxY = Math.max(min.y, max.y);
        int minZ = Math.min(min.z, max.z);
        int maxZ = Math.max(min.z, max.z);
        
        // Calculate the number of positions
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;
        int totalPositions = width * height * depth;
        
        Vector3i[] positions = new Vector3i[totalPositions];
        int index = 0;
        
        // Generate all positions within the bounding box
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    positions[index++] = new Vector3i(x, y, z);
                }
            }
        }
        
        return positions;
    }
}
