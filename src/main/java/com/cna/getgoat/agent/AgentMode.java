package com.cna.getgoat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.cna.getgoat.map.campaigns.node.BranchNode;
import com.cna.getgoat.map.campaigns.node.CommanderAction;

import java.util.List;

/**
 * A working mode for AbstractAgent.
 *
 * Each mode defines: which tools the agent can call, what system prompt to use,
 * how to build the conversation context for a round, and how to dispatch tool
 * calls to local implementations.
 *
 * This is the single extension point — adding a new capability (intel gathering,
 * simulation, training) means implementing this interface, not writing a new
 * agent class.
 */
public interface AgentMode {

    /** Unique mode identifier, e.g. "command", "intel", "simulate". */
    String getName();

    /**
     * Default system prompt for this mode. May be overridden by the per-agent
     * system prompt file configured in the workspace commander config.
     */
    String getDefaultSystemPrompt();

    /**
     * OpenAI-format tool definitions available in this mode.
     * Returned as an array of {@code {"type":"function","function":{...}}} objects.
     */
    JsonNode getToolDefinitions();

    /**
     * Build the initial user message(s) for the current round.
     *
     * Called by AbstractAgent at the start of executeRound(). The returned messages
     * are appended directly to the LLM conversation (after the system prompt and
     * any past-round replay handled by AbstractAgent).
     *
     * @param agent    the calling AbstractAgent (provides access to managers)
     * @param current  the target BranchNode for this round
     * @param chain    ancestor chain from root to current (inclusive)
     * @param guidance optional human guidance for this round
     * @return array of user messages to append
     */
    JsonNode buildCurrentRoundContext(AbstractAgent agent, BranchNode current,
                                       List<BranchNode> chain, String guidance);

    /**
     * Dispatch a single tool call within this mode.
     *
     * @param agent    the calling AbstractAgent (provides access to managers)
     * @param toolName the name of the tool being called
     * @param args     the tool arguments as a JSON object
     * @return the tool result as a JSON string
     */
    String dispatchTool(AbstractAgent agent, String toolName, JsonNode args);

    /**
     * Called after all sub-rounds complete successfully.
     * Hook for modes that need to perform post-round actions (e.g. save to branch tree).
     */
    default void onRoundComplete(AbstractAgent agent, CommanderAction action) {}

    /**
     * Build the system message for the LLM conversation.
     * Uses the configured system prompt if available, otherwise the mode default.
     */
    default JsonNode buildSystemMessage(AbstractAgent agent) {
        String prompt = agent.getConfig().getSystemPrompt();
        if (prompt == null || prompt.isEmpty()) prompt = getDefaultSystemPrompt();
        return agent.msg("system", prompt);
    }

    /**
     * Format past rounds for replay in the conversation.
     * Default implementation handles the standard CommanderAction sub-round replay.
     * Modes can override if they have different history structures.
     */
    default ArrayNode buildPastRoundReplay(AbstractAgent agent, BranchNode curNode,
                                           List<BranchNode> chain, int index) {
        // Default: delegate to agent's built-in replay logic
        return agent.replayPastRound(curNode, chain, index);
    }
}
