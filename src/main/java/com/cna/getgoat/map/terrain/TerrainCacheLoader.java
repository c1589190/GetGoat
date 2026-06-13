package com.cna.getgoat.map.terrain;

/**
 * Adapter that wraps an existing {@link TerrainCache} (memory-mapped binary
 * file) behind the {@link TerrainLoader} interface.
 *
 * <p>This is the <b>primary runtime loader</b> — all terrain queries in
 * normal operation flow through this adapter to the off-heap
 * {@code MappedByteBuffer}.  Zero heap allocation for terrain data.
 *
 * <p>For performance-critical operations that need raw {@code short}
 * elevation values (e.g. the 3×3 standard-deviation computation in relief
 * profiling), callers can obtain the underlying {@link TerrainCache} via
 * {@link #getCache()} and read directly.
 */
public class TerrainCacheLoader implements TerrainLoader {

    private final TerrainCache cache;

    public TerrainCacheLoader(TerrainCache cache) {
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        this.cache = cache;
    }

    /** Expose the raw cache for callers that need {@code getElevationShort()} etc. */
    public TerrainCache getCache() { return cache; }

    // ---- TerrainLoader ----

    @Override public double getCellSizeDegrees() { return cache.getCellSize(); }
    @Override public int getRows()               { return cache.getRows(); }
    @Override public int getCols()               { return cache.getCols(); }
    @Override public boolean isReady()           { return cache.isOpen(); }

    @Override
    public TerrainType getTerrain(int row, int col) {
        return cache.getTerrain(row, col);
    }

    @Override
    public double getElevation(int row, int col) {
        return cache.getElevation(row, col);
    }

    @Override
    public boolean isClassified(int row, int col) {
        return cache.isClassified(row, col);
    }
}
