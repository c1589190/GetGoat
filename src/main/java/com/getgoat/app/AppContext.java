package com.getgoat.app;

import com.getgoat.agent.AgentManager;
import com.getgoat.map.ConfigManager;
import com.getgoat.map.branch.BranchManager;
import com.getgoat.map.manager.MapManager;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.tools.*;

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
    public final BranchManager branchManager;
    public final AgentManager agentManager;
    public final ToolRegistry toolRegistry;

    private boolean initialized;

    public AppContext() {
        this.mapManager = new MapManager();
        this.unitsManager = new UnitsManager();
        this.branchManager = new BranchManager(unitsManager);
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

        double res = ConfigManager.getGridCellSize();
        LOG.info("Initializing map at " + res + "° resolution...");
        mapManager.initialize(res);
        var stats = mapManager.getStats();
        LOG.info("Map ready: " + stats.totalCells() + " cells, "
            + stats.landCells() + " land, " + stats.waterCells() + " water");

        // Load workspace
        String wsDir = ConfigManager.getProperty("workspace.dir", null);
        if (wsDir != null && !wsDir.isEmpty()) {
            branchManager.setWorkspace(wsDir);
            Path wsPath = Paths.get(wsDir);
            if (!wsPath.isAbsolute()) wsPath = Paths.get(System.getProperty("user.dir")).resolve(wsDir);
            agentManager.setWorkspace(wsPath);
            LOG.info("Workspace loaded from " + wsDir + " (" + unitsManager.count() + " units)");
        }
    }

    /** Quick init for MCP (terrain grid only, no workspace). */
    public void initializeLight() {
        if (initialized) return;
        initialized = true;
        mapManager.initialize(ConfigManager.getGridCellSize());
        LOG.info("Map ready (light init): " + mapManager.getStats().totalCells() + " cells");
    }
}
