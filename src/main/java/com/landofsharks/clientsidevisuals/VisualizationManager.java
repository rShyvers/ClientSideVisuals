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
 * <h2>Two rendering modes</h2>
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
 *   <li><b>Debug visuals</b> — colored wireframe cuboids rendered via
 *       {@link com.hypixel.hytale.protocol.packets.player.DisplayDebug} packets. Adjacent
 *       positions are automatically merged into larger AABBs by the rendering pipeline.</li>
 *   <li><b>Fake doors</b> — client-side block replacements sent via
 *       {@link com.hypixel.hytale.protocol.packets.world.ServerSetBlock} packets. Clearing
 *       or disabling a fake-door set automatically reverts the affected positions to their
 *       real world blocks.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Manual unregistration is optional; all managers are cleared automatically when the server
 * shuts down. Call {@link ClientsideVisualizationHandler#unregisterManager} explicitly only
 * if you need to stop visualizations before plugin shutdown.
 *
 * <h2>Thread safety</h2>
 * Internal state is stored in {@link java.util.concurrent.ConcurrentHashMap} instances.
 * Individual read-modify-write operations (e.g. {@link #clearAndRegisterVectorSet}) are not
 * atomically synchronized, so concurrent mutations for the same player from multiple threads
 * are not recommended.
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
     * The inner maps are also {@link ConcurrentHashMap} instances created lazily on first write.
     */
    @Nonnull
    private final Map<UUID, Map<String, ClientsideVisualizationHandler.VectorSet>> playerVectorSets = new ConcurrentHashMap<>();

    /**
     * Create a new {@code VisualizationManager} with the given system ID and register it for
     * ticker-based persistence.
     *
     * @param systemId a unique, human-readable identifier for the owning system
     *                 (e.g. {@code "greenhouse_main"}, {@code "zone_system"});
     *                 used in log output to distinguish managers
     * @throws NullPointerException if {@code systemId} is {@code null}
     */
    public VisualizationManager(@Nonnull String systemId) {
        this.systemId = Objects.requireNonNull(systemId, "System ID cannot be null");
        ClientsideVisualizationHandler.registerManager(this);
    }

    /**
     * Create a new {@code VisualizationManager} with an auto-generated system ID and register
     * it for ticker-based persistence. The generated ID is based on the instance's identity
     * hash code (e.g. {@code "system_1234567890"}) and is stable for the lifetime of this object.
     */
    public VisualizationManager() {
        this.systemId = "system_" + System.identityHashCode(this);
        ClientsideVisualizationHandler.registerManager(this);
    }

    /**
     * Return the unique system ID for this manager.
     * Used in log output and for distinguishing managers during debugging.
     *
     * @return the system ID; never {@code null}
     */
    @Nonnull
    public String getSystemId() {
        return systemId;
    }

    /**
     * Register a debug-visual set for a player, replacing all other sets previously
     * registered for that player in this manager.
     * The set is stored under the key {@code "default"}.
     *
     * @param playerId  the target player's UUID
     * @param positions block positions to render wireframe cuboids at
     * @param color     RGB color with each component in the range {@code [0.0, 1.0]}
     * @throws NullPointerException if any argument is {@code null}
     */
    public void setDebugVisuals(@Nonnull UUID playerId, @Nonnull Vector3i[] positions, @Nonnull Vector3f color) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");
        Objects.requireNonNull(color, "Color cannot be null");
        
        ClientsideVisualizationHandler.VectorSet set = ClientsideVisualizationHandler.VectorSet.debugVisual(positions, color);
        clearAndRegisterVectorSet(playerId, "default", set);
    }

    /**
     * Register a debug-visual set for a player using an axis-aligned bounding box, replacing
     * all other sets previously registered for that player in this manager.
     * All block positions within the box (inclusive on all faces) are expanded and stored
     * under the key {@code "default"}.
     *
     * @param playerId the target player's UUID
     * @param min      the minimum corner of the bounding box (component order need not be
     *                 pre-sorted; components are normalized internally)
     * @param max      the maximum corner of the bounding box
     * @param color    RGB color with each component in the range {@code [0.0, 1.0]}
     * @throws NullPointerException if any argument is {@code null}
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
     * Register a self-referential fake-door set for a player, replacing all other sets
     * previously registered for that player in this manager.
     * Each position in {@code positions} will have its own real world block data re-sent to
     * the client. This is useful for forcing a client refresh of blocks that may be out of
     * sync rather than displaying a different block.
     * Stored under the key {@code "default"}.
     *
     * @param playerId  the target player's UUID
     * @param positions block positions whose real block state will be re-sent to the client
     * @throws NullPointerException if any argument is {@code null}
     */
    public void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i[] positions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");

        setFakeDoors(playerId, positions, positions);
    }

    /**
     * Register a fake-door set for a player, replacing all other sets previously registered
     * for that player in this manager.
     * For each index {@code i}, the block found at {@code toPositions[i]} in the world will
     * be sent to the client at {@code fromPositions[i]}.
     * Stored under the key {@code "default"}.
     *
     * @param playerId      the target player's UUID
     * @param fromPositions client-side positions where fake blocks will appear
     * @param toPositions   world positions whose block data will be read; must have the same
     *                      length as {@code fromPositions}
     * @throws NullPointerException if any argument is {@code null}
     */
    private void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i[] fromPositions, @Nonnull Vector3i[] toPositions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(fromPositions, "From positions cannot be null");
        Objects.requireNonNull(toPositions, "To positions cannot be null");

        ClientsideVisualizationHandler.VectorSet set = ClientsideVisualizationHandler.VectorSet.fakeDoors(fromPositions, toPositions);
        clearAndRegisterVectorSet(playerId, "default", set);
    }

    /**
     * Register a self-referential fake-door set for a player using a bounding box, replacing
     * all other sets previously registered for that player in this manager.
     * All positions within the box are expanded and each position's real block data is
     * re-sent to the client. Stored under the key {@code "default"}.
     *
     * @param playerId the target player's UUID
     * @param min      the minimum corner of the bounding box
     * @param max      the maximum corner of the bounding box
     * @throws NullPointerException if any argument is {@code null}
     */
    public void setFakeDoors(@Nonnull UUID playerId, @Nonnull Vector3i min, @Nonnull Vector3i max) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(min, "Min position cannot be null");
        Objects.requireNonNull(max, "Max position cannot be null");
        
        Vector3i[] positions = generatePositionsInBoundingBox(min, max);
        setFakeDoors(playerId, positions);
    }

    /**
     * Remove a single visualization set from a player's registered sets.
     * If the removed set is a {@link ClientsideVisualizationHandler.VectorSetType#FAKE_DOORS}
     * set, real block data is immediately re-sent to the client to revert the visual change.
     * If removing this set leaves the player with no remaining sets, the player entry is also
     * removed from internal storage.
     *
     * @param playerId the target player's UUID
     * @param setId    the identifier of the set to remove
     * @return the removed {@link ClientsideVisualizationHandler.VectorSet},
     *         or {@code null} if no set with that ID existed for the player
     * @throws NullPointerException if any argument is {@code null}
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

    /**
     * Add a debug-visual set for a player without disturbing other registered sets.
     * If a set with the same {@code setId} already exists for this player it is replaced.
     *
     * @param playerId  the target player's UUID
     * @param setId     a unique identifier for this set within the manager
     *                  (e.g. {@code "zone_boundary"}, {@code "pending_blocks"})
     * @param positions block positions to render wireframe cuboids at
     * @param color     RGB color with each component in the range {@code [0.0, 1.0]}
     * @throws NullPointerException if any argument is {@code null}
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
     * Add a debug-visual set for a player using a bounding box, without disturbing other
     * registered sets. All positions within the box (inclusive) are expanded before storage.
     * If a set with the same {@code setId} already exists for this player it is replaced.
     *
     * @param playerId the target player's UUID
     * @param setId    a unique identifier for this set within the manager
     * @param min      the minimum corner of the bounding box
     * @param max      the maximum corner of the bounding box
     * @param color    RGB color with each component in the range {@code [0.0, 1.0]}
     * @throws NullPointerException if any argument is {@code null}
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
     * Add a self-referential fake-door set for a player without disturbing other registered sets.
     * Each position's own real world block data will be re-sent to the client on each ticker cycle.
     * If a set with the same {@code setId} already exists for this player it is replaced.
     *
     * @param playerId  the target player's UUID
     * @param setId     a unique identifier for this set within the manager
     * @param positions block positions whose real block state will be re-sent to the client
     * @throws NullPointerException if any argument is {@code null}
     */
    public void addFakeDoors(@Nonnull UUID playerId, @Nonnull String setId, @Nonnull Vector3i[] positions) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(positions, "Positions cannot be null");

        addFakeDoors(playerId, setId, positions, positions);
    }

    /**
     * Add a fake-door set for a player without disturbing other registered sets.
     * For each index {@code i}, the block at {@code toPositions[i]} will be sent to the
     * client at {@code fromPositions[i]}. If a set with the same {@code setId} already
     * exists for this player it is replaced.
     *
     * @param playerId      the target player's UUID
     * @param setId         a unique identifier for this set within the manager
     * @param fromPositions client-side positions where fake blocks will appear
     * @param toPositions   world positions whose block data will be read; should have the
     *                      same length as {@code fromPositions}
     * @throws NullPointerException if any argument is {@code null}
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
     * Add a self-referential fake-door set for a player using a bounding box, without
     * disturbing other registered sets. All positions within the box are expanded before
     * storage. If a set with the same {@code setId} already exists for this player it is replaced.
     *
     * @param playerId the target player's UUID
     * @param setId    a unique identifier for this set within the manager
     * @param min      the minimum corner of the bounding box
     * @param max      the maximum corner of the bounding box
     * @throws NullPointerException if any argument is {@code null}
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
     * Return a snapshot of all visualization sets currently registered for a player.
     * Returns a defensive copy; mutations to the returned map do not affect internal state.
     * Called by {@link ClientsideVisualizerService} on each ticker cycle.
     *
     * @param playerId the target player's UUID
     * @return a mutable copy of the set-ID-to-{@link ClientsideVisualizationHandler.VectorSet}
     *         map, or an empty map if the player has no registered sets
     * @throws NullPointerException if {@code playerId} is {@code null}
     */
    @Nonnull
    Map<String, ClientsideVisualizationHandler.VectorSet> getAll(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        
        Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
        return sets != null ? new HashMap<>(sets) : new HashMap<>();
    }

    /**
     * Remove all visualization sets for a player from this manager.
     * Any {@link ClientsideVisualizationHandler.VectorSetType#FAKE_DOORS} sets are reverted
     * by re-sending real block data to the client before removal.
     *
     * @param playerId the target player's UUID
     * @throws NullPointerException if {@code playerId} is {@code null}
     */
    public void clear(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Map<String, ClientsideVisualizationHandler.VectorSet> removedSets = playerVectorSets.remove(playerId);
        revertFakeDoorSets(playerId, removedSets);
    }

    /**
     * Remove a specific visualization set across all players registered in this manager.
     * If the removed set is a {@link ClientsideVisualizationHandler.VectorSetType#FAKE_DOORS}
     * set, real block data is immediately re-sent to each affected client to revert the visual
     * change. Players who have no set under {@code setId} are silently skipped.
     *
     * @param setId the identifier of the set to remove from every player
     * @throws NullPointerException if {@code setId} is {@code null}
     */
    public void clearAll(@Nonnull String setId) {
        Objects.requireNonNull(setId, "Set ID cannot be null");

        for (UUID playerId : new HashSet<>(playerVectorSets.keySet())) {
            Map<String, ClientsideVisualizationHandler.VectorSet> sets = playerVectorSets.get(playerId);
            if (sets == null) {
                continue;
            }

            ClientsideVisualizationHandler.VectorSet removed = sets.remove(setId);
            revertFakeDoorSet(playerId, removed);
            if (sets.isEmpty()) {
                playerVectorSets.remove(playerId);
            }
        }
    }


    /**
     * Force an immediate render of all sets currently registered for a player.
     * Batches all debug visuals and dispatches all fake-door sets in a single
     * {@link ClientsideVisualizationHandler#applyVectorSets} call.
     * Returns silently if the player has no registered sets or their {@link PlayerRef}
     * is no longer valid.
     * Called internally; prefer relying on the ticker for routine persistence.
     *
     * @param playerId the target player's UUID
     * @throws NullPointerException if {@code playerId} is {@code null}
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
     * Remove all visualization sets for a player, reverting any fake doors.
     * Equivalent to {@link #clear(UUID)}; provided as a named counterpart to
     * {@link #enable(UUID)} for call-site clarity.
     *
     * @param playerId the target player's UUID
     * @throws NullPointerException if {@code playerId} is {@code null}
     */
    public void disable(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        clear(playerId);
    }

    /**
     * Return the UUIDs of all players that currently have at least one set registered
     * in this manager. Returns a snapshot; the set may change concurrently.
     * Called by {@link ClientsideVisualizerService} to determine which players need
     * a ticker update.
     *
     * @return a mutable snapshot of the player UUID set; never {@code null}
     */
    @Nonnull
    Set<UUID> getAllPlayerIds() {
        return new HashSet<>(playerVectorSets.keySet());
    }

    /**
     * Store a {@link ClientsideVisualizationHandler.VectorSet} under the given key for a player.
     * Creates the inner map lazily if this is the first set registered for the player.
     * If a set with the same {@code setId} already exists it is silently replaced.
     *
     * @param playerId  the target player's UUID
     * @param setId     the key under which to store the set
     * @param vectorSet the set to store
     */
    private void registerVectorSet(@Nonnull UUID playerId, @Nonnull String setId, 
                                   @Nonnull ClientsideVisualizationHandler.VectorSet vectorSet) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(setId, "Set ID cannot be null");
        Objects.requireNonNull(vectorSet, "Vector set cannot be null");
        
        playerVectorSets.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(setId, vectorSet);
    }

    /**
     * Clear all existing sets for a player and then register a single new one.
     * Fake-door reversion is triggered by the {@link #clear(UUID)} call before the new
     * set is stored, so the client sees real blocks briefly before the new set is applied
     * on the next ticker cycle.
     *
     * @param playerId  the target player's UUID
     * @param setId     the key under which to store the new set
     * @param vectorSet the new set to register
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
     * Revert all {@link ClientsideVisualizationHandler.VectorSetType#FAKE_DOORS} sets in the
     * given map by re-sending real block data to the player's client.
     * Non-fake-door sets and {@code null} entries in the map are silently skipped.
     * Returns immediately if {@code sets} is {@code null} or empty, or if the player's
     * {@link PlayerRef} is no longer valid.
     *
     * @param playerId the target player's UUID
     * @param sets     the sets to revert, typically the map removed from internal storage;
     *                 may be {@code null}
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
     * Revert a single {@link ClientsideVisualizationHandler.VectorSetType#FAKE_DOORS} set by
     * re-sending real block data to the player's client.
     * Returns immediately if {@code set} is {@code null}, is not a fake-door set, or the
     * player's {@link PlayerRef} is no longer valid.
     *
     * @param playerId the target player's UUID
     * @param set      the set to revert; may be {@code null}
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
     * Generate every block position contained within an axis-aligned bounding box, inclusive
     * on all faces. Min and max components are normalized before iteration so the caller does
     * not need to guarantee that {@code min.x ≤ max.x} etc.
     * Positions are enumerated in X → Y → Z order.
     *
     * @param min one corner of the bounding box
     * @param max the opposite corner of the bounding box
     * @return array of {@code (maxX-minX+1) * (maxY-minY+1) * (maxZ-minZ+1)} positions;
     *         never {@code null}, never empty (a single-block box produces one entry)
     * @throws NullPointerException if either argument is {@code null}
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
