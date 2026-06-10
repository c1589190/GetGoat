package com.getgoat.agent;

import com.getgoat.agent.modes.CommandMode;
import com.getgoat.map.branch.BranchManager;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.manager.MapManager;

import java.nio.file.Path;

/**
 * A concrete BaseAgent pre-configured with {@link CommandMode}.
 *
 * Maintained for backward compatibility — all core logic lives in
 * {@link BaseAgent} and {@link CommandMode}.
 *
 * Subclasses (e.g. {@link LLMCommanderAgent}) only need to implement
 * {@link #callLLM} for their LLM provider.
 */
public abstract class CommanderAgent extends BaseAgent {

    @Override
    public void initialize(CommanderConfig cfg, BranchManager bm, UnitsManager um,
                           MapManager mm, Path workspace) {
        super.initialize(cfg, bm, um, mm, workspace);
        setMode(new CommandMode());
    }
}
