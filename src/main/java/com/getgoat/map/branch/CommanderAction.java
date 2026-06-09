package com.getgoat.map.branch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A commander's actions for one round, stored inside the branch node.
 *
 * Supports multi-round LLM execution: each "round" can have multiple
 * sub-rounds (tool-call iterations: recon → orders → confirm).
 *
 * <pre>
 * {
 *   "side": "japanese",
 *   "source": "llm",          // "llm" | "historical" | "manual"
 *   "guidance": "...",
 *   "subRounds": [{iteration, request, response, results}, ...],
 *   "finalPlan": [...],       // the ultimate tool_calls
 *   "rationale": "...",
 *   "guidanceAssessment": "...",
 *   "feedback": "..."
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommanderAction {

    public String side;
    public String source;       // "llm", "historical", "manual"
    public String guidance;
    public String deployment;   // legacy JSON string (backward compat)
    public List<SubRound> subRounds;
    public JsonNode finalPlan;
    public String rationale;
    public String guidanceAssessment;
    public String feedback;
    public String risks;
    public String recommendations;
    public long timestamp;

    public CommanderAction() {
        this.subRounds = new ArrayList<>();
    }

    public CommanderAction(String side, String guidance) {
        this();
        this.side = side;
        this.guidance = guidance != null ? guidance : "";
        this.source = "llm";
        this.timestamp = System.currentTimeMillis();
    }

    /** A single sub-round of LLM interaction within a commander round. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubRound {
        public int iteration;
        public JsonNode request;    // what was sent to LLM (the conversation)
        public JsonNode response;   // what LLM returned (assistant message)
        public JsonNode results;    // tool execution results
        public long timestamp;

        public SubRound() {}

        public SubRound(int iteration, JsonNode response, JsonNode results) {
            this.iteration = iteration;
            this.response = response;
            this.results = results;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
