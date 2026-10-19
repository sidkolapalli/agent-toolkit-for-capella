package org.polarsys.capella.ai.agent;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.polarsys.capella.ai.llm.LLMService.Message;

/** Model-independent orchestration; all model writes remain reviewed by the caller. */
public interface AgentRuntime extends AutoCloseable {
    ArcadiaAgent.Turn run(String systemPrompt, List<Message> history, String userMessage,
            BooleanSupplier cancelled, Consumer<ArcadiaAgent.Progress> progress);
    ArcadiaAgent.Turn finish(ArcadiaAgent.Turn previous, ArcadiaAgent.ExecutionOutcome outcome,
            BooleanSupplier cancelled, Consumer<ArcadiaAgent.Progress> progress);
    String protocolGuidance();
    /** Record execution even when the UI must stop before asking the agent to continue. */
    default void recordOutcome(ArcadiaAgent.Turn previous, ArcadiaAgent.ExecutionOutcome outcome) {}
    /**
     * Local history and plan after a recorded outcome, without provider inference.
     * Null means this runtime does not supply an outcome snapshot; callers retain
     * their existing execution feedback in that case.
     */
    default ArcadiaAgent.Turn outcomeSnapshot() { return null; }
    /** One host-bounded attempt to gather missing evidence; never authorizes model writes. */
    default boolean canContinueReadOnly(ArcadiaAgent.Turn previous) { return false; }
    default ArcadiaAgent.Turn continueRemaining(ArcadiaAgent.Turn previous, BooleanSupplier cancelled,
            Consumer<ArcadiaAgent.Progress> progress) {
        throw new IllegalStateException("Read continuation is not supported by this runtime");
    }
    /** Compile-only failures are preparation facts, separate from an execution outcome. */
    default boolean canRepairCompilation(ArcadiaAgent.Turn previous) { return false; }
    default ArcadiaAgent.Turn repairCompilation(ArcadiaAgent.Turn previous, String diagnostic,
            BooleanSupplier cancelled, Consumer<ArcadiaAgent.Progress> progress) {
        throw new IllegalStateException("Compilation repair is not supported by this runtime");
    }
    @Override default void close() {}
}
