package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.agent.AgentMode;
import com.cna.getgoat.agent.AbstractAgent;
import com.cna.getgoat.config.CommanderConfig;
import com.cna.getgoat.agent.mode.IntelMode;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.MapManager;
import com.cna.getgoat.map.campaigns.UnitsManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * MCP tool: Generate a tactical scenario from a description.
 *
 * Uses IntelMode to search for information, validate terrain, create units,
 * and build an initial BranchTree.
 */
public class GenerateScenarioTool implements ToolUnit {

    private final MapManager mapManager;
    private final UnitsManager unitsManager;
    private final NodesManager branchManager;

    public GenerateScenarioTool(MapManager mm, UnitsManager um, NodesManager bm) {
        this.mapManager = mm;
        this.unitsManager = um;
        this.branchManager = bm;
    }

    @Override
    public String getName() { return "generate_scenario"; }

    @Override
    public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "根据描述生成战术场景（搜索信息→创建单位→建立分支树）。适用于从历史战役名或单位列表生成完整初始态势。");

        ObjectNode props = ToolRegistry.objectNode();
        props.set("description", ToolRegistry.stringParam("场景描述，例如'台儿庄战役国军防守日军进攻'或详细单位列表"));
        props.set("name", ToolRegistry.stringParam("场景名称，用作分支树名称"));
        props.set("strategy", ToolRegistry.stringParam("策略标签: historical/alt1/alt2，默认historical"));

        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object");
        params.set("properties", props);
        ArrayNode required = ToolRegistry.arrayNode().add("description");
        params.set("required", required);
        def.set("parameters", params);
        return def;
    }

    @Override
    public JsonNode execute(JsonNode args) throws Exception {
        String description = args.has("description") ? args.get("description").asText() : null;
        String name = args.has("name") ? args.get("name").asText() : "Generated Scenario";
        String strategy = args.has("strategy") ? args.get("strategy").asText() : "historical";

        if (description == null || description.isEmpty())
            return ToolRegistry.objectNode().put("error", "description is required");

        // Create an agent configured for IntelMode
        CommanderConfig cfg = new CommanderConfig();
        cfg.setName("IntelAgent");
        cfg.setSide("intel");
        CommanderConfig.LlmConfig llm = new CommanderConfig.LlmConfig();
        llm.provider = "anthropic";
        llm.model = "claude-sonnet-4-6";
        // API key from env — if not set, agent will use LLM knowledge without live search
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        llm.apiKey = apiKey != null ? apiKey : "";
        cfg.setLlm(llm);

        // Create a temporary agent with IntelMode
        AbstractAgent intelAgent = new com.cna.getgoat.agent.LLMCommanderAgent();
        intelAgent.initialize(cfg, branchManager, unitsManager, mapManager,
            Path.of(System.getProperty("user.dir")));
        intelAgent.setMode(new IntelMode());

        try {
            // Create a temporary placeholder tree for the agent to work in
            var tempTree = branchManager.createTree("_scenario_generation_temp");
            String treeId = tempTree.getId();
            String nodeId = tempTree.getRoot().getId();

            // Run the IntelMode round — the LLM will call search_web, query_terrain,
            // batch_create_units, create_branch_tree via tool calls
            var action = intelAgent.executeFullRound(treeId, nodeId, description, 8);

            // Clean up: remove the temp tree if a real one was created
            var trees = branchManager.listTrees();
            if (trees.size() > 1) {
                branchManager.deleteTree(treeId);
            }

            ObjectNode result = ToolRegistry.objectNode();
            result.put("ok", true);
            result.put("description", description);
            result.put("name", name);
            result.put("rationale", action.rationale != null ? action.rationale : "");
            result.put("subRounds", action.subRounds.size());
            result.put("unitCount", unitsManager.count());

            // Find the created tree
            trees = branchManager.listTrees();
            if (!trees.isEmpty()) {
                var tree = trees.get(trees.size() - 1);
                result.put("treeId", tree.getId());
                result.put("treeName", tree.getName());
                result.put("nodeCount", tree.countNodes());
            }

            return result;
        } catch (Exception e) {
            ObjectNode err = ToolRegistry.objectNode();
            err.put("ok", false);
            err.put("error", "Intel generation failed: " + e.getMessage());
            return err;
        }
    }
}
