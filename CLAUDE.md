# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Compile and run the HTTP map server (starts on port 8080)
MAVEN_OPTS="-Xmx2g -Xms1g" mvn exec:java

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

### Entry Point

Single entry: `com.cna.getgoat.Main`
- `--http` (default): starts HTTP server on :8080 serving frontend + REST API
- `--mcp`: starts JSON-RPC 2.0 server over stdio for Claude Code tool integration

### Core Subsystems

**Config** (`com.cna.getgoat.config`)
- `ConfigsManager` — instance-based configuration (was static `ConfigManager`). Loads from `config.properties`
- `ConfigsLoader` — persistence layer for config updates
- `CommanderConfig` — per-commander LLM configuration (side, provider, model, apiKey)

**Map** (`com.cna.getgoat.map`)
- `MapManager` — static singleton. Central entry for terrain queries, radius queries, A\* pathfinding, great-circle distance, road-network shortest path, relief profiles
- `terrain/` — `TerrainGenerator`, `TerrainCache` (memory-mapped `terrain_cache.bin`), `TerrainGrid`, `ElevationLoader`, `ReliefMapLoader`, `TerrainClassifier`, `TerrainOverrideStore`, `TerrainCell`, `TerrainType`
- `network/` — `RoadNetwork`, `RoadNode`, `RoadSegment`, `RiverNetwork`
- `geometry/` — `SphericalEngine` (haversine/Vincenty distance, bearing, great-circle paths), `ProjectionUtil`, `GeoBounds`, `GeoPoint`
- `data/` — `AdminDivisionLoader`, `GeoJsonLoader`, `GeoJsonWriter`, `ShapeFileReader`, `DataPathConfig`, `AdminDivision`
- `annotation/` — `Region`, `RegionLabel`, `Annotation`, `RadiusQueryResult`

**Campaigns** (`com.cna.getgoat.map.campaigns`)
- `CampaignsManager` — takes a campaign name, loads workspace, owns NodesManager + UnitsManager
- `NodesManager` — (was `BranchManager`) IO for campaign nodes (branch trees)
- `UnitsManager` — CRUD for game units with code-based identity
- `node/` — `BranchTree`, `BranchNode`, `UnitSnapshot`, `UnitChange`, `Movement`, `CommanderAction`
- `unit/` — `Unit` data model
- `intel/` — `IntelCertainty`, `PhantomUnit`, `SideIntelEntry`, `SideIntelMap`

**Agent** (`com.cna.getgoat.agent`)
- `AbstractAgent` — (was `BaseAgent`) generic agent interface. Takes SystemPrompt + Cache + LLManager
- `Cache` — OpenAI-format LLM message cache, built from `CacheUnit` sequence
- `CacheUnit` — single conversation message (system/user/assistant/tool roles)
- `Commander` — extends AbstractAgent, multi-round deployment with returnable Cache
- `Simulator` — extends AbstractAgent, accepts CacheUnit[] for other commanders
- `mode/` — `CommandMode` (6 tools), `IntelMode` (6 tools), `SimulateMode` (9 tools)
- `tool/` — `ToolsManager` + 18 Tool definitions (pure Java, not Web API)
- `sim/` — `CombatResolver` (Lanchester equations), `EngagementDetector`, `MovementResolver`, `SimulationResult`

**LLM** (`com.cna.getgoat.llm`)
- `LLManager` — unified LLM call manager. Routes to OpenAI/Anthropic providers with format translation
- `provider/` — (planned) `AnthropicProvider`, `OpenAIProvider` for format isolation

**Web** (`com.cna.getgoat.web` — planned)
- `APIManager` — API routing dispatch
- `HttpHandler` — REST API handlers
- `MCPHandler` — MCP stdio JSON-RPC interface
- `MapUI` — static frontend file service

### Frontend

Vanilla JS + Leaflet 1.9.4 in `frontend/`. Entry: `index.html`. Scripts: `map-core.js` (map init, rendering, interaction), `map-terrain.js`, `map-layers.js`, `map-labels.js`, `branch-tree.js` (branch tree panel), `api.js`. CSS: `map.css`, `branch-panel.css`.

### Configuration

`config.properties` in project root overrides defaults in `ConfigsManager.java`. Key settings:
- `grid.cellSizeDegrees` — terrain resolution (0.015625 = ~1.7km at equator; 0.03125 default)
- `terrain.reliefTiff` / `terrain.fallbackTiff` — GeoTIFF elevation sources
- `workspace.dir` — campaign scenario directory (e.g., `workspaces/songhu-1937`)
- `relief.elevThreshold` / `relief.roughThreshold` — relief classification parameters
- `llm.provider` / `llm.model` / `llm.endpoint` / `llm.apiKey` / `llm.maxTokens` — LLM defaults

### Workspace Structure

Each workspace (campaign scenario) contains:
```
workspaces/<name>/
  units.json          — unit definitions
  branches.json       — branch trees with all nodes/snapshots
  terrain_overrides.json — terrain override cells
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
