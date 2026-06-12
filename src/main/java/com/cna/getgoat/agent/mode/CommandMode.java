package com.cna.getgoat.agent.mode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.agent.AgentMode;
import com.cna.getgoat.agent.AbstractAgent;
import com.cna.getgoat.map.campaigns.node.BranchNode;
import com.cna.getgoat.map.campaigns.node.CommanderAction;
import com.cna.getgoat.map.geometry.SphericalEngine;
import com.cna.getgoat.map.terrain.TerrainCell;

import java.util.List;

/**
 * Command mode — the agent acts as a battlefield commander.
 *
 * Tools: query_terrain, query_radius, get_distance, move_unit, create_unit, delete_unit.
 * The agent first scouts terrain, then issues movement/creation/deletion orders.
 */
public class CommandMode implements AgentMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() { return "command"; }

    @Override
    public String getDefaultSystemPrompt() {
        return """
            你是一名战场指挥官。你需要：
            1. 先用侦察工具（query_terrain, query_radius, get_distance）了解地形和敌我态势
            2. 再用移动工具（move_unit, create_unit, delete_unit）下达作战命令
            3. 每步解释你的决策理由
            4. 完成部署后用不含 tool_calls 的回复总结你的作战计划

            注意：
            - 单位位置已在上方表格中完整列出，无需重复查询位置
            - 移动单位时确保新位置在地形上是合理的
            - 优先考虑地形优势（高地、河流、城市）""";
    }

    @Override
    public JsonNode getToolDefinitions() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(defineTool("query_terrain", "查询指定坐标的地形类型、海拔、颜色",
            new String[][]{{"lat", "number", "纬度"}, {"lng", "number", "经度"}}));
        tools.add(defineTool("query_radius", "查询指定坐标半径内的地形、城市、道路",
            new String[][]{{"lat", "number", "纬度"}, {"lng", "number", "经度"},
                           {"r", "number", "半径(km)，默认50"}}));
        tools.add(defineTool("get_distance", "计算两点间的地球表面距离和方位角",
            new String[][]{{"lat1", "number", "起点纬度"}, {"lng1", "number", "起点经度"},
                           {"lat2", "number", "终点纬度"}, {"lng2", "number", "终点经度"}}));
        tools.add(defineTool("move_unit", "移动一个单位到新位置",
            new String[][]{{"code", "string", "单位代号"}, {"lat", "number", "目标纬度"},
                           {"lng", "number", "目标经度"}}));
        tools.add(defineTool("create_unit", "创建一个新单位",
            new String[][]{{"code", "string", "单位代号(唯一)"}, {"name", "string", "单位名称"},
                           {"source", "string", "所属阵营"}, {"type", "string", "类型(infantry/naval/air/civilian/supply/generic)"},
                           {"lat", "number", "纬度"}, {"lng", "number", "经度"}}));
        tools.add(defineTool("delete_unit", "删除/消灭一个单位",
            new String[][]{{"code", "string", "单位代号"}}));
        return tools;
    }

    @Override
    public JsonNode buildCurrentRoundContext(AbstractAgent agent, BranchNode current,
                                              List<BranchNode> chain, String guidance) {
        CommanderAction curAction = current.getCommanderAction(agent.getConfig().getSide());

        if (curAction != null && curAction.subRounds != null && !curAction.subRounds.isEmpty()) {
            // Resuming: replay current round's sub-rounds + prompt to continue
            StringBuilder header = new StringBuilder();
            header.append("## Round ").append(current.getRound())
                .append(" — ").append(current.getName()).append(" (当前)\n\n");
            header.append(agent.formatFullUnitTable());
            if (curAction.guidance != null && !curAction.guidance.isEmpty())
                header.append("\n### 指令要点\n").append(curAction.guidance);

            ArrayNode messages = MAPPER.createArrayNode();
            messages.add(agent.msg("user", header.toString()));

            for (CommanderAction.SubRound sr : curAction.subRounds) {
                if (sr.response != null) messages.add(agent.toAssistantMessageInternal(sr.response));
                if (sr.results != null && !sr.results.has("done") && sr.results.isArray()) {
                    for (JsonNode r : sr.results) {
                        ObjectNode toolMsg = MAPPER.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", r.has("tool_call_id")
                            ? r.get("tool_call_id").asText() : "");
                        String sc = r.has("content")
                            ? (r.get("content").isTextual() ? r.get("content").asText() : r.get("content").toString())
                            : r.toString();
                        toolMsg.put("content", sc);
                        messages.add(toolMsg);
                    }
                }
            }
            messages.add(agent.msg("user",
                "工具结果已返回。请继续制定计划，如需移动部队请使用 move_unit/create_unit/delete_unit。如计划完成请回复不含 tool_calls 的总结。"));
            return messages;

        } else {
            // Fresh round
            StringBuilder intel = new StringBuilder();
            intel.append("## Round ").append(current.getRound())
                .append(" — ").append(current.getName()).append(" (当前)\n\n");

            // Use SideIntelMap if available (post-simulation), otherwise full table
            var intelMap = current.getSideIntelMap(agent.getConfig().getSide());
            if (intelMap != null && !intelMap.getEntries().isEmpty()) {
                // Build unit tables grouped by source for the intel brief
                var unitsBySource = new java.util.LinkedHashMap<String, java.util.List<com.cna.getgoat.map.campaigns.unit.Unit>>();
                for (var u : agent.getUnitsManager().listAll()) {
                    unitsBySource.computeIfAbsent(u.getSource(), k -> new java.util.ArrayList<>()).add(u);
                }
                intel.append(intelMap.toIntelBrief(unitsBySource)).append("\n");
            } else {
                // No intel map yet (first round) — show everything
                intel.append(agent.formatFullUnitTable()).append("\n");
                intel.append("### 态势\n").append(agent.formatIntel(current)).append("\n");
            }

            intel.append("\n");
            if (guidance != null && !guidance.isEmpty())
                intel.append("### 指令要点 (必须遵守)\n").append(guidance).append("\n\n");
            intel.append("### 可用工具\n");
            intel.append("- get_distance(lat1,lng1,lat2,lng2) — 计算距离\n");
            intel.append("- query_terrain(lat,lng) — 查询单点地形\n");
            intel.append("- query_radius(lat,lng,r) — 查询半径内地形/城市\n");
            intel.append("- move_unit(code,lat,lng) — 移动部队\n");
            intel.append("- create_unit(code,name,source,type,lat,lng) — 新增部队\n");
            intel.append("- delete_unit(code) — 消灭/撤退部队\n\n");
            intel.append("注意：你只能看到情报地图中列出的敌军。未列出的敌军位置未知。");

            return agent.msg("user", intel.toString());
        }
    }

    @Override
    public String dispatchTool(AbstractAgent agent, String toolName, JsonNode args) {
        double lat, lng, r, lat1, lng1, lat2, lng2;
        switch (toolName) {
            case "get_distance":
                lat1 = agent.getArgD(args, "lat1"); lng1 = agent.getArgD(args, "lng1");
                lat2 = agent.getArgD(args, "lat2"); lng2 = agent.getArgD(args, "lng2");
                double dist = SphericalEngine.haversineDistance(lat1, lng1, lat2, lng2);
                double bearing = SphericalEngine.bearing(lat1, lng1, lat2, lng2);
                return String.format("{\"distance_km\":%.1f,\"bearing_deg\":%.1f}", dist, bearing);

            case "query_terrain":
                lat = agent.getArgD(args, "lat"); lng = agent.getArgD(args, "lng");
                TerrainCell cell = agent.getMapManager().getTerrainAt(lat, lng);
                if (cell == null) return "{\"error\":\"no data\"}";
                return String.format("{\"terrain\":\"%s\",\"elevation\":%.0f,\"color\":\"%s\"}",
                    cell.getTerrain().getDisplayName(), cell.getElevationMeters(), cell.getColorHex());

            case "query_radius":
                lat = agent.getArgD(args, "lat"); lng = agent.getArgD(args, "lng");
                r = agent.getArgD(args, "r", 50);
                var qr = agent.getMapManager().queryRadiusEnhanced(lat, lng, r);
                StringBuilder sb = new StringBuilder("{");
                if (qr.centerCell() != null) {
                    sb.append("\"terrain\":\"").append(qr.centerCell().getTerrain().getDisplayName()).append("\",");
                    sb.append("\"elevation\":").append((int)qr.centerCell().getElevationMeters()).append(",");
                }
                sb.append("\"cities\":[");
                boolean first = true;
                for (var c : qr.cities()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"name\":\"").append(esc(c.getName())).append("\",\"lat\":")
                      .append(c.getCenter().getLatitude()).append(",\"lng\":")
                      .append(c.getCenter().getLongitude()).append("}");
                }
                sb.append("],\"roadNodes\":").append(qr.roadNodes().size());
                sb.append(",\"roadSegments\":").append(qr.roadSegments().size());
                if (qr.terrainProfile() != null) {
                    sb.append(",\"terrainProfile\":{");
                    boolean ft = true;
                    for (var e : qr.terrainProfile().entrySet()) {
                        if (!ft) sb.append(",");
                        ft = false;
                        sb.append("\"").append(e.getKey().getDisplayName()).append("\":").append(e.getValue());
                    }
                    sb.append("}");
                }
                sb.append("}");
                return sb.toString();

            case "move_unit":
                String code = agent.getArgS(args, "code");
                lat = agent.getArgD(args, "lat"); lng = agent.getArgD(args, "lng");
                agent.getUnitsManager().move(code, lat, lng);
                return "{\"moved\":\"" + esc(code) + "\",\"lat\":" + lat + ",\"lng\":" + lng + "}";

            case "create_unit":
                code = agent.getArgS(args, "code");
                String uname = agent.getArgS(args, "name", code);
                String src = agent.getArgS(args, "source", agent.getConfig().getSide());
                String type = agent.getArgS(args, "type", "infantry");
                lat = agent.getArgD(args, "lat"); lng = agent.getArgD(args, "lng");
                try {
                    agent.getUnitsManager().create(code, uname, src, type, lat, lng);
                    return "{\"created\":\"" + esc(code) + "\",\"lat\":" + lat + ",\"lng\":" + lng + "}";
                } catch (IllegalArgumentException e) {
                    return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
                }

            case "delete_unit":
                code = agent.getArgS(args, "code");
                boolean del = agent.getUnitsManager().delete(code);
                return "{\"deleted\":" + del + ",\"code\":\"" + esc(code) + "\"}";

            default:
                return "{\"error\":\"unknown tool: " + esc(toolName) + "\"}";
        }
    }

    // ---- Helpers ----

    private ObjectNode defineTool(String name, String desc, String[][] params) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", name);
        fn.put("description", desc);
        ObjectNode props = fn.putObject("parameters").putObject("properties");
        ArrayNode required = fn.putObject("parameters").putArray("required");
        fn.with("parameters").put("type", "object");
        for (String[] p : params) {
            props.putObject(p[0]).put("type", p[1]).put("description", p[2]);
            if (p.length < 4 || !"optional".equals(p[3])) required.add(p[0]);
        }
        return tool;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }
}
