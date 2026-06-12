package com.cna.getgoat;

import com.cna.getgoat.agent.AgentManager;
import com.cna.getgoat.config.ConfigsManager;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.MapManager;
import com.cna.getgoat.map.campaigns.UnitsManager;
import com.cna.getgoat.agent.tool.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Shared application context — initializes all subsystems once.
 * Used by both HTTP server (MapDemo) and MCP stdio server (McpServer).
 */
public class AppContext {

    private static final Logger LOG = Logger.getLogger(AppContext.class.getName());

    public final MapManager mapManager;
    public final UnitsManager unitsManager;
    public final NodesManager branchManager;
    public final AgentManager agentManager;
    public final ToolRegistry toolRegistry;

    private boolean initialized;

    public AppContext() {
        this.mapManager = new MapManager();
        this.unitsManager = new UnitsManager();
        this.branchManager = new NodesManager(unitsManager);
        this.agentManager = new AgentManager(branchManager, unitsManager, mapManager);
        this.toolRegistry = new ToolRegistry();
        registerTools();
    }

    private void registerTools() {
        toolRegistry.register(new GetTerrainTool(mapManager));
        toolRegistry.register(new RadiusQueryTool(mapManager));
        toolRegistry.register(new DistanceTool());
        toolRegistry.register(new FindPathTool(mapManager));
        toolRegistry.register(new ReliefProfileTool(mapManager));
        toolRegistry.register(new UnitListTool(unitsManager));
        toolRegistry.register(new UnitGetTool(unitsManager));
        toolRegistry.register(new UnitCreateTool(unitsManager));
        toolRegistry.register(new UnitMoveTool(unitsManager));
        toolRegistry.register(new UnitDeleteTool(unitsManager));
        toolRegistry.register(new BranchListTool(branchManager));
        toolRegistry.register(new BranchNodesTool(branchManager));
        toolRegistry.register(new BranchApplyTool(branchManager));
        toolRegistry.register(new BranchSaveRoundTool(branchManager));
        toolRegistry.register(new WorkspaceSaveTool(branchManager, unitsManager));
        toolRegistry.register(new WorkspaceLoadTool(branchManager, unitsManager));
        toolRegistry.register(new GenerateScenarioTool(mapManager, unitsManager, branchManager));
        toolRegistry.register(new SimulateRoundTool(mapManager, unitsManager, branchManager));
    }

    /** Full initialization: terrain grid + workspace + agents. */
    public void initialize() {
        if (initialized) return;
        initialized = true;

        double res = ConfigsManager.getGridCellSize();
        LOG.info("Initializing map at " + res + "° resolution...");
        mapManager.initialize(res);
        var stats = mapManager.getStats();
        LOG.info("Map ready: " + stats.totalCells() + " cells, "
            + stats.landCells() + " land, " + stats.waterCells() + " water");

        // Load workspace
        String wsDir = ConfigsManager.getProperty("workspace.dir", null);
        if (wsDir != null && !wsDir.isEmpty()) {
            branchManager.setWorkspace(wsDir);
            Path wsPath = Paths.get(wsDir);
            if (!wsPath.isAbsolute()) wsPath = Paths.get(System.getProperty("user.dir")).resolve(wsDir);
            agentManager.setWorkspace(wsPath);
            // Propagate workspace to terrain override store (loads terrain_overrides.json if present)
            mapManager.setWorkspaceForOverrides(branchManager.getWorkspaceDir());
            LOG.info("Workspace loaded from " + wsDir + " (" + unitsManager.count() + " units)");
        }
    }

    /** Quick init for MCP (terrain grid only, no workspace). */
    public void initializeLight() {
        if (initialized) return;
        initialized = true;
        mapManager.initialize(ConfigsManager.getGridCellSize());
        LOG.info("Map ready (light init): " + mapManager.getStats().totalCells() + " cells");
    }
}
