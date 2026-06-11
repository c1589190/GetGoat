# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Compile and run the HTTP map server (starts on port 8080)
MAVEN_OPTS="-Xmx2g -Xms1g" mvn exec:java -Dexec.mainClass="com.getgoat.app.Main"

# Compile only
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=SphericalEngineTest

# Run the MCP stdio server manually (Claude Code auto-launches via .claude/mcp.json)
./mcp-server.sh
```

The MCP server requires `mcp-classpath.txt` generated once by `mvn dependency:build-classpath`. The shell script handles this automatically.

## Architecture

GetGoat is a **grand-strategy wargame map system** — a Java 17 Maven project with a Leaflet web frontend. It classifies real-world terrain at hexagonal-raster resolution and exposes it through both a REST API and a Claude Code MCP server.

### Two Entry Points

Both entry points share the same core subsystems:

| Entry Point | Class | Protocol | Purpose |
|---|---|---|---|
| `MapDemo` | `com.getgoat.app.MapDemo` | HTTP on :8080 | Web frontend + REST API |
| `McpServer` | `com.getgoat.tools.McpServer` | JSON-RPC 2.0 over stdio | Claude Code tool integration |

### Core Subsystems

**Terrain Pipeline** (`com.getgoat.map.terrain`)
- `TerrainGenerator` orchestrates classification: tries ETOPO1 elevation → GeoTIFF HSV relief → simulated fallback
- `TerrainCache` is a memory-mapped file (`terrain_cache.bin`, ~1GB at 0.015625° resolution) — random-access terrain read without loading into heap
- `TerrainGrid` wraps the cache with grid-indexing math (lat/lng ↔ row/col)
- `ElevationLoader` loads ETOPO1 binary elevation; `ReliefMapLoader` parses HYP_HR_SR_OB_DR GeoTIFF for HSV-based classification
- `TerrainClassifier` assigns terrain types (OCEAN, PLAINS, HILLS, MOUNTAIN, etc.) based on elevation + roughness

**Map API** (`com.getgoat.map.manager.MapManager`)
- The central entry point for all terrain queries: point terrain lookup, radius queries (with road/city/region overlay), A\* grid pathfinding, great-circle distance, road-network shortest path, relief profiles
- Owns `TerrainGrid`, `RoadNetwork`, `RiverNetwork`, `AdminDivisionLoader`, user-defined `Region`s, labels, and annotations
- Radius query results are LRU-cached (256 entries)

**Units** (`com.getgoat.map.manager.UnitsManager`)
- CRUD for game units with code-based identity, metadata (source, status, color, type, description), and lat/lng position
- Supports list-by-source/status/name filters plus keyword search

**Branch/Decision Tree** (`com.getgoat.map.branch`)
- `BranchManager` manages multiple `BranchTree` objects persisted to `branches.json`
- Each tree is a hierarchy of `BranchNode`s representing wargame rounds
- Nodes capture `UnitSnapshot`s (frozen unit state) and compute `UnitChange`s and `Movement`s via parent↔child diff
- Applying a node restores its snapshot to the live unit map

**Commander Agent** (`com.getgoat.agent`)
- Per-side LLM-powered agents that generate deployment plans for branch nodes
- `AgentManager` loads commander configs from `workspaces/<name>/commanders/<side>/config.json`
- `LLMCommanderAgent` calls external LLM APIs; `CommanderAgent` is the abstract base
- Supports multi-round deployment, system prompt editing, and human feedback

**MCP Tools** (`com.getgoat.tools`)
- `ToolUnit` abstract class defines an OpenAI-compatible function definition + execution
- `ToolRegistry` registers tools and dispatches tool calls
- 15 tools: `get_terrain`, `query_radius`, `get_distance`, `find_path`, `get_relief_profile`, `list_units`, `get_unit`, `create_unit`, `move_unit`, `delete_unit`, `list_branches`, `get_branch_nodes`, `apply_branch`, `save_branch_round`, `save_workspace`, `load_workspace`

**Geometry** (`com.getgoat.map.geometry`)
- `SphericalEngine` — haversine distance, Vincenty distance, bearing, great-circle paths, point-in-polygon, destination from bearing+distance

### Frontend

Vanilla JS + Leaflet 1.9.4 in `frontend/`. Entry: `index.html`. Scripts: `map-core.js` (map init, rendering, interaction), `map-terrain.js`, `map-layers.js`, `map-labels.js`, `branch-tree.js` (branch tree panel), `api.js`. CSS: `map.css`, `branch-panel.css`.

### Configuration

`config.properties` in project root overrides defaults in `ConfigManager.java`. Key settings:
- `grid.cellSizeDegrees` — terrain resolution (0.015625 = ~1.7km at equator, ~265M cells; 0.03125 default)
- `terrain.reliefTiff` / `terrain.fallbackTiff` — GeoTIFF elevation sources
- `workspace.dir` — campaign scenario directory (e.g., `workspaces/taierzhuang-1938`)
- `relief.elevThreshold` / `relief.roughThreshold` — relief classification parameters

### Workspace Structure

Each workspace (campaign scenario) contains:
```
workspaces/<name>/
  units.json          — unit definitions
  branches.json       — branch trees with all nodes/snapshots
  commanders/<side>/
    config.json       — {"side": "nationalist", "llm": {"apiKey": "..."}}
    prompt.txt        — system prompt for that side
```

## Key Dependencies

- **JTS Topology Suite** (`org.locationtech.jts`) — geometry operations (Point, Polygon, STRtree spatial index)
- **Jackson** — JSON serialization/deserialization
- **JUnit 5** — testing
- No Spring or other frameworks; the HTTP server is `com.sun.net.httpserver.HttpServer`

## Geospatial Data Pipeline

1. Natural Earth GeoTIFF (HYP_HR_SR_OB_DR.tif, 21600×10800) → `ReliefMapLoader` classifies by HSV color
2. Natural Earth shapefiles (roads, rivers, admin boundaries, coastlines) → JTS geometries
3. First run builds `terrain_cache.bin` (memory-mapped, ~15s at 0.03125°); subsequent runs open it instantly
4. Terrain colors are configured in `terrain-colors.json` with HSV rules per terrain type
