package com.cna.getgoat.map.campaigns;

import com.cna.getgoat.map.campaigns.unit.Unit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages a campaign (workspace) — owns NodesManager and UnitsManager for one campaign.
 * Takes a campaign name and reads from workspaces/{name}/.
 */
public class CampaignsManager {
    private static final Logger LOG = Logger.getLogger(CampaignsManager.class.getName());

    private final NodesManager nodesManager;
    private final UnitsManager unitsManager;
    private Path workspaceDir;
    private String campaignName;

    public CampaignsManager(String campaignName) {
        this.campaignName = campaignName;
        this.unitsManager = new UnitsManager();
        this.nodesManager = new NodesManager(unitsManager);
        if (campaignName != null && !campaignName.isEmpty()) {
            loadCampaign(campaignName);
        }
    }

    /** Switch to a different campaign, discarding current state. */
    public void switchCampaign(String name) {
        this.campaignName = name;
        loadCampaign(name);
    }

    /** List all available campaigns under workspaces/. */
    public static List<String> listCampaigns() {
        List<String> campaigns = new ArrayList<>();
        Path wsRoot = Paths.get("workspaces");
        if (Files.isDirectory(wsRoot)) {
            try (var dirs = Files.newDirectoryStream(wsRoot, p -> Files.isDirectory(p))) {
                for (Path d : dirs) {
                    campaigns.add(d.getFileName().toString());
                }
            } catch (IOException ignored) {}
        }
        campaigns.sort(String::compareTo);
        return campaigns;
    }

    private void loadCampaign(String name) {
        Path ws = Paths.get("workspaces", name);
        if (!Files.isDirectory(ws)) {
            LOG.warning("Campaign workspace not found: " + ws);
            return;
        }
        this.workspaceDir = ws.toAbsolutePath().normalize();
        nodesManager.setWorkspace(workspaceDir.toString());
        LOG.info("Campaign loaded: " + name + " from " + workspaceDir);
    }

    public NodesManager getNodesManager() { return nodesManager; }
    public UnitsManager getUnitsManager() { return unitsManager; }
    public Path getWorkspaceDir() { return workspaceDir; }
    public String getCampaignName() { return campaignName; }
    public boolean isReady() { return workspaceDir != null && nodesManager.isWorkspaceReady(); }

    /** Reload workspace data from disk. */
    public String reload() {
        if (workspaceDir == null) return "{\"error\":\"no campaign loaded\"}";
        return nodesManager.reloadWorkspace();
    }
}
