package com.cna.getgoat.agent.mode;

import com.cna.getgoat.map.campaigns.NodesManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.agent.AgentMode;
import com.cna.getgoat.agent.AbstractAgent;
import com.cna.getgoat.map.campaigns.node.BranchNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Intel mode — the agent gathers intelligence and generates tactical maps.
 *
 * Tools: search_web, query_terrain, query_radius, batch_create_units, create_branch_tree.
 * The agent researches a scenario, validates locations against terrain, creates units,
 * and builds a complete initial branch tree.
 *
 * This is a SKELETON — full implementation in Phase 2.
 */
public class IntelMode implements AgentMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() { return "intel"; }

    @Override
    public String getDefaultSystemPrompt() {
        return """
            你是一名情报分析员和地图生成专家。你需要：
            1. 根据用户给出的战役描述，搜索相关信息（参战部队、兵力、位置）
            2. 使用地形工具验证候选部署位置的合理性
            3. 生成完整的初始态势（创建所有参战单位并放置到合理位置）
            4. 创建分支树保存初始态势

            单位类型：infantry（步兵）, naval（海军）, air（空军）, civilian（民众）, supply（补给）, generic（通用）
            状态：active（活跃）, idle（待命）, moving（移动中）, engaged（交战中）, retreating（撤退中）, destroyed（已消灭）""";
    }

    @Override
    public JsonNode getToolDefinitions() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(defineTool("search_web", "搜索网络获取历史战役信息（参战方、兵力、位置等）",
            new String[][]{{"query", "string", "搜索关键词"}}));
        tools.add(defineTool("query_terrain", "查询指定坐标的地形类型、海拔",
            new String[][]{{"lat", "number", "纬度"}, {"lng", "number", "经度"}}));
        tools.add(defineTool("query_radius", "查询指定坐标半径内的地形、城市、道路",
            new String[][]{{"lat", "number", "纬度"}, {"lng", "number", "经度"},
                           {"r", "number", "半径(km)，默认50"}}));
        tools.add(defineTool("fetch_url", "获取指定URL的完整内容（用于阅读搜索结果中的详细文章）",
            new String[][]{{"url", "string", "要获取的URL"}}));
        tools.add(defineTool("batch_create_units", "批量创建单位",
            new String[][]{{"units", "string", "JSON数组[{code,name,source,type,lat,lng,status?,color?,description?}]"}}));
        tools.add(defineTool("create_branch_tree", "创建新的分支树（初始态势）",
            new String[][]{{"name", "string", "战役/场景名称"},
                           {"strategy", "string", "策略标签(historical/alt1/alt2)"}}));
        return tools;
    }

    @Override
    public JsonNode buildCurrentRoundContext(AbstractAgent agent, BranchNode current,
                                              List<BranchNode> chain, String guidance) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("## 场景生成\n\n");
        if (guidance != null && !guidance.isEmpty()) {
            ctx.append("### 用户描述\n").append(guidance).append("\n\n");
        }
        ctx.append("### 当前地图上的单位 (").append(agent.getUnitsManager().count()).append(")\n");
        ctx.append(agent.formatFullUnitTable()).append("\n\n");
        ctx.append("请先搜索相关信息，然后在地图上创建参战单位，最后创建分支树保存态势。");
        return agent.msg("user", ctx.toString());
    }

    @Override
    public String dispatchTool(AbstractAgent agent, String toolName, JsonNode args) {
        switch (toolName) {
            case "search_web":
                return searchWeb(agent, agent.getArgS(args, "query"));
            case "query_terrain":
                return queryTerrain(agent, agent.getArgD(args, "lat"), agent.getArgD(args, "lng"));
            case "query_radius":
                return queryRadius(agent, agent.getArgD(args, "lat"),
                    agent.getArgD(args, "lng"), agent.getArgD(args, "r", 50));
            case "fetch_url":
                return fetchUrl(agent, agent.getArgS(args, "url"));
            case "batch_create_units":
                return batchCreateUnits(agent, agent.getArgS(args, "units"));
            case "create_branch_tree":
                return createBranchTree(agent, agent.getArgS(args, "name"),
                    agent.getArgS(args, "strategy", "historical"));
            default:
                return "{\"error\":\"unknown intel tool: " + toolName + "\"}";
        }
    }

    // ---- Tool implementations ----

    private String searchWeb(AbstractAgent agent, String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://en.wikipedia.org/w/api.php?action=query&list=search"
                + "&srsearch=" + encoded + "&srlimit=5&format=json&origin=*";

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "GetGoat/0.1 IntelAgent")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                return "{\"error\":\"search failed, HTTP " + resp.statusCode() + "\"}";

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode results = root.at("/query/search");
            if (results == null || !results.isArray() || results.size() == 0)
                return "{\"results\":[],\"message\":\"no Wikipedia results found for: " + esc(query) + "\"}";

            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode r : results) {
                ObjectNode item = out.addObject();
                item.put("title", r.get("title").asText());
                item.put("snippet", stripHtml(r.get("snippet").asText()));
                item.put("pageid", r.get("pageid").asInt());
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.set("results", out);
            response.put("source", "wikipedia");
            return response.toString();
        } catch (Exception e) {
            return "{\"error\":\"search failed: " + esc(e.getMessage()) + "\"}";
        }
    }

    private String fetchUrl(AbstractAgent agent, String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "GetGoat/0.1 IntelAgent")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                return "{\"error\":\"fetch failed, HTTP " + resp.statusCode() + "\"}";

            // Return truncated content (max 8000 chars to avoid token blowup)
            String body = resp.body();
            if (body.length() > 8000) body = body.substring(0, 8000) + "...";
            ObjectNode out = MAPPER.createObjectNode();
            out.put("url", url);
            out.put("content", body);
            out.put("contentLength", resp.body().length());
            return out.toString();
        } catch (Exception e) {
            return "{\"error\":\"fetch failed: " + esc(e.getMessage()) + "\"}";
        }
    }

    private String queryTerrain(AbstractAgent agent, double lat, double lng) {
        var cell = agent.getMapManager().getTerrainAt(lat, lng);
        if (cell == null) return "{\"error\":\"no data\"}";
        return String.format("{\"terrain\":\"%s\",\"elevation\":%.0f,\"color\":\"%s\"}",
            cell.getTerrain().getDisplayName(), cell.getElevationMeters(), cell.getColorHex());
    }

    private String queryRadius(AbstractAgent agent, double lat, double lng, double r) {
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
        sb.append(",\"queryTimeMs\":").append(qr.queryTimeMs()).append("}");
        return sb.toString();
    }

    private String batchCreateUnits(AbstractAgent agent, String unitsJson) {
        try {
            JsonNode arr = MAPPER.readTree(unitsJson);
            if (!arr.isArray()) return "{\"error\":\"units must be a JSON array\"}";
            int created = 0;
            ArrayNode createdList = MAPPER.createArrayNode();
            for (JsonNode u : arr) {
                String code = u.has("code") ? u.get("code").asText() : null;
                if (code == null || code.isEmpty()) continue;
                String name = u.has("name") ? u.get("name").asText() : code;
                String source = u.has("source") ? u.get("source").asText() : "custom";
                String type = u.has("type") ? u.get("type").asText() : "infantry";
                double lat = u.has("lat") ? u.get("lat").asDouble() : 0;
                double lng = u.has("lng") ? u.get("lng").asDouble() : 0;
                try {
                    var unit = agent.getUnitsManager().create(code, name, source, type, lat, lng);
                    if (u.has("status")) unit.setStatus(u.get("status").asText());
                    if (u.has("color")) unit.setColor(u.get("color").asText());
                    if (u.has("description")) unit.setDescription(u.get("description").asText());
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("code", code);
                    item.put("lat", lat);
                    item.put("lng", lng);
                    createdList.add(item);
                    created++;
                } catch (IllegalArgumentException e) {
                    // duplicate code — skip
                }
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put("created", created);
            out.set("units", createdList);
            return out.toString();
        } catch (Exception e) {
            return "{\"error\":\"batch create failed: " + esc(e.getMessage()) + "\"}";
        }
    }

    private String createBranchTree(AbstractAgent agent, String name, String strategy) {
        var tree = agent.getNodesManager().createTree(name);
        // Set strategy on root node
        if (tree.getRoot() != null) {
            tree.getRoot().setStrategy(strategy != null ? strategy : "historical");
            agent.getNodesManager().saveToDisk();
        }
        return "{\"ok\":true,\"treeId\":\"" + tree.getId() + "\",\"name\":\"" + esc(name)
            + "\",\"rootNodeId\":\"" + (tree.getRoot() != null ? tree.getRoot().getId() : "")
            + "\",\"nodeCount\":" + tree.countNodes() + "}";
    }

    // ---- Helpers ----

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private ObjectNode defineTool(String name, String desc, String[][] params) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", name);
        fn.put("description", desc);
        ObjectNode ps = fn.putObject("parameters");
        ps.put("type", "object");
        ObjectNode props = ps.putObject("properties");
        ArrayNode required = ps.putArray("required");
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
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
