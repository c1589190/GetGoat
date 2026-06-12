package com.cna.getgoat.agent;

import com.cna.getgoat.config.CommanderConfig;
import com.cna.getgoat.agent.mode.CommandMode;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.UnitsManager;
import com.cna.getgoat.map.MapManager;

import java.nio.file.Path;

/**
 * A concrete AbstractAgent pre-configured with {@link CommandMode}.
 *
 * Maintained for backward compatibility — all core logic lives in
 * {@link AbstractAgent} and {@link CommandMode}.
 *
 * Subclasses (e.g. {@link LLMCommanderAgent}) only need to implement
 * {@link #callLLM} for their LLM provider.
 */
public abstract class Commander extends AbstractAgent {

    @Override
    public void initialize(CommanderConfig cfg, NodesManager bm, UnitsManager um,
                           MapManager mm, Path workspace) {
        super.initialize(cfg, bm, um, mm, workspace);
        setMode(new CommandMode());
    }
}
