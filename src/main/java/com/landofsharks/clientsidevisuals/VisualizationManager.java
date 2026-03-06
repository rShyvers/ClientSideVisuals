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
 * Manages client-side visualizations on behalf of a single system or plugin.
 *
 * <p>Each system should create its own {@code VisualizationManager} to isolate visualization
 * state and prevent set-ID collisions with other systems. The manager registers itself with
 * {@link ClientsideVisualizationHandler} on construction and is automatically refreshed by the
 * background ticker, so visualizations persist on-screen without any further action from the
 * owning system.
 *
 * <h2>Two registration modes</h2>
 * <ul>
 *   <li><b>Single mode</b> ({@code set*} methods) — registers a visualization under the
 *       reserved key {@code "default"}, clearing all previously registered sets for that
 *       player first. Use this when a system shows only one visualization at a time.</li>
 *   <li><b>Multi mode</b> ({@code add*} methods) — registers a visualization under a
 *       caller-supplied {@code setId}, leaving all other sets intact. Use this when a
 *       system needs to composite several independent visualizations simultaneously.</li>
 * </ul>
 *
 * <h2>Supported visualization types</h2>
 * <ul>
 *   <li>{@link ClientsideVisualizationHandler.VectorSet.DebugVisual} — colored wireframe
 *       cuboids. Adjacent positions are merged into larger AABBs by the rendering pipeline.</li>
 *   <li>{@link ClientsideVisualizationHandler.VectorSet.Replace} — stamps a fixed block ID
 *       onto every position client-side.</li>
 *   <li>{@link ClientsideVisualizationHandler.VectorSet.Mirror} — copies real block data from
 *       source positions to target positions client-side.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Manual unregistration is optional; all managers are cleared automatically on shutdown.
 * Call {@link ClientsideVisualizationHandler#unregisterManager} explicitly only if you need
 * to stop visualizations before plugin shutdown.
 *
 * <h2>Thread safety</h2>
 * Internal state is stored in {@link ConcurrentHashMap} instances. Individual read-modify-write
 * operations (e.g. {@link #clearAndRegisterVectorSet}) are not atomically synchronized, so
 * concurrent mutations for the same player from multiple threads are not recommended.
 */
public class VisualizationManager {

    /**
     * Human-readable identifier for this manager instance.
     * Used in log messages to distinguish output from different systems.
     */
    @Nonnull
    private final String systemId;

    /**
     * Primary visualization state: player UUID → (set ID → vector set).
     * The outer map is a {@link ConcurrentHashMap} for safe cross-thread iteration by the ticker.
     * Inner maps are also {@link ConcurrentHashMap} instances, created lazily on first write.
     */
    @Nonnull
    private final Map<UUID, Map<String, ClientsideVisualizationHandler.VectorSet>> playerVectorSets
            = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Create a new {@code VisualizationManager} with the given system ID and register it for
     * ticker-based persistence.
     *
     * @param systemId a unique, human-readable identifier for the owning system
     *                 (e.g. {@code "greenhouse_main"}, {@code "zone_system"})
     * @throws NullPointerException if {@code systemId} is {@code null}
     */
    public VisualizationManager(@Nonnull String systemId) {
        this.systemId = Objects.requireNonNull(systemId, "System ID cannot be null");
        ClientsideVisualizationHandler.registerManager(this);
    }

    /**
     * Create a new {@code VisualizationManager} with an auto-generated system ID and register
     * it for ticker-based persistence. The generated ID is based on the instance's identity
     * hash code (e.g. {@code "system_1234567890"}).
     */
    public VisualizationManager() {
        this.systemId = "system_" + System.identityHashCode(this);
        ClientsideVisualizationHandler.registerManager(this);
    }

    /**
     * Return the unique system ID for this manager.
     *
     * @return the system ID; never {@code null}
     */
    @Nonnull
    public String getSystemId() {
        return systemId;
    }

    // -------------------------------------------------------------------------
    // Single-mode (set*) — replaces all existing sets for the player
    // -------------------------------------------------------------------------

    /**
     * Register a debug-visual set for a player under the key {@code "default"},
     * replacing all previously registered sets for that player.
     *
     * @param playerId  the target player's UUID
     * @param positions block positions to render wireframe cuboids at
     * @param color     RGB color with each component in the range {@code [0.0, 1.0]}
     */
    public void setDebugVisuals(@Nonnull UUID playerId, @Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");

        clearAndRegisterVectorSet(playerId, "default",
                new ClientsideVisualizationHandler.VectorSet.DebugVisual(positions, color));
    }

    /**
     * Register a debug-visual set for a player using a bounding box, replacing all previously
     * registered sets for that player. All positions within the box (inclusive) are expanded
     * before storage. Stored under the key {@code "default"}.
     *
     * @param playerId the target player's UUID
     * @param min      one corner of the bounding box
     * @param max      the opposite corner of the bounding box
     * @param color    RGB color with each component in the range {@code [0.0, 1.0]}
     */
    public void setDebugVisuals(@Nonnull UUID playerId, @Nonnull Vector3i min, @Nonnull Vector3i max,
                                @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");

        setDebugVisuals(playerId, expandBoundingBox(min, max), color);
    }

    /**
     * Stamp a fixed block ID onto every position for a player, replacing all previously
     * registered sets for that player. Stored under the key {@code "default"}.
     *
     * @param playerId  the target player's UUID
     * @param positions positions that will display the replacement block
     * @param blockId   the block ID to send to every position
     * @param rotation  rotation byte to apply (use {@code 0} for no rotation)
     */
    public void setReplace(@Nonnull UUID playerId, @Nonnull Vector3i[] positions,
                           int blockId, byte rotation) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");

        clearAndRegisterVectorSet(playerId, "default",
                new ClientsideVisualizationHandler.VectorSet.Replace(positions, blockId, rotation));
    }

    /**
     * Stamp a fixed block ID (with no rotation) onto every position for a player, replacing
     * all previously registered sets for that player. Stored under the key {@code "default"}.
     *
     * @param playerId  the target player's UUID
     * @param positions positions that will display the replacement block
     * @param blockId   the block ID to send to every position
     */
    public void setReplace(@Nonnull UUID playerId, @Nonnull Vector3i[] positions, int blockId) {
        setReplace(playerId, positions, blockId, (byte) 0);
    }

    /** Legacy Support */
    public void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i[] positions) {
        setReplace(playerId, positions, 0, (byte) 0);
    }

    /** Legacy Support */
    public void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i min, @Nonnull Vector3i max) {
        setReplace(playerId, expandBoundingBox(min, max), 0, (byte) 0);
    }


    /**
     * Mirror block data from {@code fromPositions} onto {@code toPositions} for a player,
     * replacing all previously registered sets for that player. Stored under the key
     * {@code "default"}.
     *
     * @param playerId     the target player's UUID
     * @param fromPositions world positions whose block data (id, filler, rotation) will be read
     * @param toPositions   client-side positions where that block data will be displayed
     */
    public void setMirror(@Nonnull UUID playerId, @Nonnull Vector3i[] fromPositions,
                          @Nonnull Vector3i[] toPositions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(fromPositions, "From positions cannot be null");
        Objects.requireNonNull(toPositions, "To positions cannot be null");

        clearAndRegisterVectorSet(playerId, "default",
                new ClientsideVisualizationHandler.VectorSet.Mirror(fromPositions, toPositions));
    }

    // -------------------------------------------------------------------------
    // Multi-mode (add*) — leaves existing sets intact
    // -------------------------------------------------------------------------

    /**
     * Add a debug-visual set for a player without disturbing other registered sets.
     * If a set with the same {@code setId} already exists for this player it is replaced.
     *
     * @param playerId  the target player's UUID
     * @param setId     a unique identifier for this set within the manager
     * @param positions block positions to render wireframe cuboids at
     * @param color     RGB color with each component in the range {@code [0.0, 1.0]}
     */
    public void addDebugVisuals(@Nonnull UUID playerId, @Nonnull String setId,
                                @Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");

        registerVectorSet(playerId, setId,
                new ClientsideVisualizationHandler.VectorSet.DebugVisual(positions, color));
    }

    /**
     * Add a debug-visual set for a player using a bounding box, without disturbing other
     * registered sets. If a set with the same {@code setId} already exists it is replaced.
     *
     * @param playerId the target player's UUID
     * @param setId    a unique identifier for this set within the manager
     * @param min      one corner of the bounding box
     * @param max      the opposite corner of the bounding box
     * @param color    RGB color with each component in the range {@code [0.0, 1.0]}
     */
    public void addDebugVisuals(@Nonnull UUID playerId, @Nonnull String setId,
                                @Nonnull Vector3i min, @Nonnull Vector3i max, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");

        addDebugVisuals(playerId, setId, expandBoundingBox(min, max), color);
    }

    /**
     * Add a replace set for a player without disturbing other registered sets.
     * If a set with the same {@code setId} already exists it is replaced.
     *
     * @param playerId  the target player's UUID
     * @param setId     a unique identifier for this set within the manager
     * @param positions positions that will display the replacement block
     * @param blockId   the block ID to send to every position
     * @param rotation  rotation byte to apply (use {@code 0} for no rotation)
     */
    public void addReplace(@Nonnull UUID playerId, @Nonnull String setId,
                           @Nonnull Vector3i[] positions, int blockId, byte rotation) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");

        registerVectorSet(playerId, setId,
                new ClientsideVisualizationHandler.VectorSet.Replace(positions, blockId, rotation));
    }

    /**
     * Add a replace set (with no rotation) for a player without disturbing other registered sets.
     * If a set with the same {@code setId} already exists it is replaced.
     *
     * @param playerId  the target player's UUID
     * @param setId     a unique identifier for this set within the manager
     * @param positions positions that will display the replacement block
     * @param blockId   the block ID to send to every position
     */
    public void addReplace(@Nonnull UUID playerId, @Nonnull String setId,
                           @Nonnull Vector3i[] positions, int blockId) {
        addReplace(playerId, setId, positions, blockId, (byte) 0);
    }

    /** Legacy Support */
    public void addFakeDoors(@Nonnull UUID playerId, @Nonnull String setId, @Nonnull Vector3i[] positions) {
        addReplace(playerId, setId, positions, 0, (byte) 0);
    }

    /** Legacy Support */
    public void addFakeDoors(@Nonnull UUID playerId, @Nonnull String setId, @Nonnull Vector3i min, @Nonnull Vector3i max) {
        addReplace(playerId, setId, expandBoundingBox(min, max), 0, (byte) 0);
    }


    /**
     * Add a mirror set for a player without disturbing other registered sets.
     * If a set with the same {@code setId} already exists it is replaced.
     *
     * @param playerId      the target player's UUID
     * @param setId         a unique identifier for this set within the manager
     * @param fromPositions world positions whose block data will be read
     * @param toPositions   client-side positions where that block data will be displayed
     */
    public void addMirror(@Nonnull UUID playerId, @Nonnull String setId,
                          @Nonnull Vector3i[] fromPositions, @Nonnull Vector3i[] toPositions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(fromPositions, "From positions cannot be null");
        Objects.requireNonNull(toPositions, "To positions cannot be null");

        registerVectorSet(playerId, setId,
                new ClientsideVisualizationHandler.VectorSet.Mirror(fromPositions, toPositions));
    }

    // -------------------------------------------------------------------------
    // Removal
    // -------------------------------------------------------------------------

    /**
     * Remove all visualization sets for a player from this manager.
     * Any {@link ClientsideVisualizationHandler.VectorSet.Replace} or
     * {@link ClientsideVisualizationHandler.VectorSet.Mirror} sets are reverted
     * by re-sending real block data to the client before removal.
     *
     * @param playerId the target player's UUID
     */
    public void clear(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Map<String, ClientsideVisualizationHandler.VectorSet> removed = playerVectorSets.remove(playerId);
        revertBlockSets(playerId, removed);
    }

    /**
     * Remove a single visualization set from a player's registered sets.
     * If the removed set modifies client blocks ({@code Replace} or {@code Mirror}),
     * real block data is immediately re-sent to revert the visual change.
     * If removing this set leaves the player with no remaining sets, the player entry
     * is also removed from internal storage.
     *
     * @param playerId the target player's UUID
     * @param setId    the identifier of the set to remove
     * @return the removed set, or {@code null} if no set with that ID existed
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
        revertBlockSet(playerId, removed);
        if (sets.isEmpty()) {
            playerVectorSets.remove(playerId);
        }
        return removed;
    }

    /**
     * Remove a specific set ID across all players registered in this manager.
     * Block-modifying sets are reverted for each affected player.
     * Players with no set under {@code setId} are silently skipped.
     *
     * @param setId the identifier of the set to remove from every player
     */
    public void clearAll(@Nonnull String setId) {
        Objects.requireNonNull(setId, "Set ID cannot be null");

        for (UUID playerId : new HashSet<>(playerVectorSets.keySet())) {
            Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
            if (sets == null) {
                continue;
            }
            ClientsideVisualizationHandler.VectorSet removed = sets.remove(setId);
            revertBlockSet(playerId, removed);
            if (sets.isEmpty()) {
                playerVectorSets.remove(playerId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle (called by the ticker service)
    // -------------------------------------------------------------------------

    /**
     * Force an immediate render of all sets currently registered for a player.
     * Returns silently if the player has no registered sets or their {@link PlayerRef}
     * is no longer valid. Prefer relying on the ticker for routine persistence.
     *
     * @param playerId the target player's UUID
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

        ClientsideVisualizationHandler.applyVectorSets(playerRef, playerId, sets.values());
    }

    /**
     * Remove all visualization sets for a player and revert any block-modifying sets.
     * Equivalent to {@link #clear(UUID)}; provided as a named counterpart to
     * {@link #enable(UUID)} for call-site clarity.
     *
     * @param playerId the target player's UUID
     */
    public void disable(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        clear(playerId);
    }

    /**
     * Return a snapshot of all visualization sets currently registered for a player.
     * Returns a defensive copy; mutations to the returned map do not affect internal state.
     * Called by {@link ClientsideVisualizerService} on each ticker cycle.
     *
     * @param playerId the target player's UUID
     * @return a mutable copy of the set-ID → VectorSet map, or an empty map if the player
     *         has no registered sets
     */
    @Nonnull
    Map<String, ClientsideVisualizationHandler.VectorSet> getAll(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
        return sets != null ? new HashMap<>(sets) : new HashMap<>();
    }

    /**
     * Return the UUIDs of all players that currently have at least one set registered.
     * Returns a snapshot; the set may change concurrently.
     * Called by {@link ClientsideVisualizerService} to determine which players need a tick.
     *
     * @return a mutable snapshot of the player UUID set; never {@code null}
     */
    @Nonnull
    Set<UUID> getAllPlayerIds() {
        return new HashSet<>(playerVectorSets.keySet());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Store a {@link ClientsideVisualizationHandler.VectorSet} under the given key for a player.
     * Creates the inner map lazily if this is the first set registered for the player.
     * Silently replaces any existing set with the same {@code setId}.
     */
    private void registerVectorSet(@Nonnull UUID playerId, @Nonnull String setId,
                                   @Nonnull ClientsideVisualizationHandler.VectorSet vectorSet) {
        playerVectorSets.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(setId, vectorSet);
    }

    /**
     * Clear all existing sets for a player and register a single new one.
     * Block-modifying sets are reverted before the new set is stored.
     */
    private void clearAndRegisterVectorSet(@Nonnull UUID playerId, @Nonnull String setId,
                                           @Nonnull ClientsideVisualizationHandler.VectorSet vectorSet) {
        clear(playerId);
        registerVectorSet(playerId, setId, vectorSet);
    }

    /**
     * Revert all block-modifying sets ({@code Replace} and {@code Mirror}) in the given map
     * by re-sending real block data to the player's client.
     * {@code null} entries and {@code DebugVisual} sets are silently skipped.
     * Returns immediately if {@code sets} is {@code null} or empty, or if the player ref is invalid.
     */
    private void revertBlockSets(@Nonnull UUID playerId,
                                 @Nullable Map<String, ClientsideVisualizationHandler.VectorSet> sets) {
        if (sets == null || sets.isEmpty()) {
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        for (ClientsideVisualizationHandler.VectorSet set : sets.values()) {
            revertBlockSet(playerRef, set);
        }
    }

    /**
     * Revert a single block-modifying set by re-sending real block data to the player's client.
     * Returns immediately if {@code set} is {@code null}, is a {@code DebugVisual}, or the
     * player ref is invalid.
     */
    private void revertBlockSet(@Nonnull UUID playerId,
                                @Nullable ClientsideVisualizationHandler.VectorSet set) {
        if (set == null) {
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        revertBlockSet(playerRef, set);
    }

    /**
     * Revert a single block-modifying set using an already-resolved {@link PlayerRef}.
     * {@code DebugVisual} sets are silently ignored.
     */
    private void revertBlockSet(@Nonnull PlayerRef playerRef,
                                @Nullable ClientsideVisualizationHandler.VectorSet set) {
        switch (set) {
            case ClientsideVisualizationHandler.VectorSet.Replace r ->
                    ClientsideVisualizationHandler.revertPositions(playerRef, r.positions());
            case ClientsideVisualizationHandler.VectorSet.Mirror m ->
                    ClientsideVisualizationHandler.revertPositions(playerRef, m.toPositions());
            default -> { /* DebugVisual — nothing to revert */ }
        }
    }

    /**
     * Generate every block position within an axis-aligned bounding box, inclusive on all faces.
     * Min and max components are normalized so the caller does not need to guarantee ordering.
     * Positions are enumerated in X → Y → Z order.
     *
     * @param min one corner of the bounding box
     * @param max the opposite corner of the bounding box
     * @return array of {@code (dx+1)*(dy+1)*(dz+1)} positions; never {@code null} or empty
     */
    private Vector3i[] expandBoundingBox(@Nonnull Vector3i min, @Nonnull Vector3i max) {
        int minX = Math.min(min.x, max.x), maxX = Math.max(min.x, max.x);
        int minY = Math.min(min.y, max.y), maxY = Math.max(min.y, max.y);
        int minZ = Math.min(min.z, max.z), maxZ = Math.max(min.z, max.z);

        Vector3i[] positions = new Vector3i[(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)];
        int index = 0;
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