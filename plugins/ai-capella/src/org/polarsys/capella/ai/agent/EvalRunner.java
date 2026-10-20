package org.polarsys.capella.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.llm.LLMService.Message;
import org.polarsys.capella.ai.scripting.GroovyCompilationValidator;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.session.SelectionContext;

/**
 * Headless evaluation: runs one task through the same agent loop as the chat (read continuation,
 * compile repair, reviewed edits, budget continuation) with every proposal auto-approved, then
 * scores it only by reading the task's checks back from the native model. The agent's own claims
 * never count. Run on a disposable copy of a model: approved edits commit and save.
 */
public final class EvalRunner {
    /** Same caps as the chat: three proposals per request, four automatic continuations. */
    private static final int MAX_PROPOSALS = 3, MAX_CONTINUATIONS = 4;

    public record Task(String id, String prompt, List<ArcadiaAgent.Postcondition> checks) {
        public Task {
            if (id == null || id.isBlank() || prompt == null || prompt.isBlank() || checks == null || checks.isEmpty()) {
                throw new IllegalArgumentException("An eval task needs an id, a prompt and at least one check");
            }
            checks = List.copyOf(checks);
        }
    }

    /**
     * {@code detail} is the execution or preparation feedback behind a blocked ending, bounded; empty otherwise.
     * {@code edits} has one entry per applied proposal: its step IDs, how many checks the agent declared, and
     * which steps those checks verified. It explains a plan left pending.
     */
    public record Result(String id, boolean passed, String checks, int requests, int toolCalls, int proposals,
            int committed, int repairs, int continuations, String ending, String detail, long durationMs,
            List<Map<String, Object>> edits) {
        public Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id); json.put("passed", passed); json.put("requests", requests); json.put("tool_calls", toolCalls);
            json.put("proposals", proposals); json.put("committed", committed); json.put("repairs", repairs);
            json.put("continuations", continuations); json.put("ending", ending); json.put("detail", detail);
            json.put("duration_ms", durationMs); json.put("edits", edits);
            json.put("checks", checks);
            return json;
        }
    }

    private EvalRunner() {}

    /** {"tasks":[{"id":..., "prompt":..., "checks":[{kind, type, name, layer} or {kind, id, ...}]}]} */
    public static List<Task> parse(String json) {
        Object decoded = AgentJson.parse(json, 2_000_000);
        if (!(decoded instanceof Map<?, ?> file) || !(file.get("tasks") instanceof List<?> items)) {
            throw new IllegalArgumentException("An eval file is {\"tasks\": [...]}");
        }
        List<Task> tasks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> task)) throw new IllegalArgumentException("Each eval task is an object");
            List<ArcadiaAgent.Postcondition> checks = new ArrayList<>();
            if (task.get("checks") instanceof List<?> declared) for (Object check : declared) {
                if (!(check instanceof Map<?, ?> fields) || !(fields.get("kind") instanceof String kind)) {
                    throw new IllegalArgumentException("Each check needs a kind");
                }
                Map<String, Object> target = new LinkedHashMap<>();
                fields.forEach((key, value) -> { if (!"kind".equals(key)) target.put(String.valueOf(key), value); });
                checks.add(new ArcadiaAgent.Postcondition("eval", kind, target));
            }
            tasks.add(new Task(String.valueOf(task.get("id")), (String) task.get("prompt"), checks));
        }
        return tasks;
    }

    /** Call from a background thread; each runtime from {@code runtimes} serves one request. */
    public static Result run(Session session, Task task, Supplier<AgentRuntime> runtimes, String systemPrompt,
            Function<Session, ModelVerification.Snapshot> validator) {
        long started = System.nanoTime();
        int requests = 0, toolCalls = 0, proposals = 0, committed = 0, repairs = 0, continuations = 0;
        String ending = "complete", detail = "";
        List<Message> history = List.of();
        List<Map<String, Object>> edits = new ArrayList<>();
        String input = task.prompt();
        requests:
        for (;;) {
            ArcadiaAgent.Turn turn = null;
            int requestProposals = 0;
            try (AgentRuntime agent = runtimes.get()) {
                turn = agent.run(systemPrompt, history, input, () -> false, progress -> {});
                for (;;) {
                    if (!turn.hasProposal()) {
                        if (agent.canContinueReadOnly(turn)) { turn = agent.continueRemaining(turn, () -> false, progress -> {}); continue; }
                        break;
                    }
                    if (requestProposals >= MAX_PROPOSALS) {
                        turn = withTermination(turn, TaskTermination.budget("Three proposals were handled in this request.", turn.plan()));
                        break;
                    }
                    GroovyCompilationValidator.Report compiled = GroovyExecutor.compileDecoded(turn.proposalCode(), () -> false, 10_000);
                    if (!compiled.success()) {
                        if (compiled.repairable() && agent.canRepairCompilation(turn)) {
                            repairs++;
                            turn = agent.repairCompilation(turn, compiled.formatForRepair(), () -> false, progress -> {});
                            continue;
                        }
                        ending = "blocked: proposal did not compile";
                        detail = bounded(compiled.formatForRepair());
                        break requests;
                    }
                    requestProposals++;
                    proposals++;
                    ReviewedEdit.Outcome edit = ReviewedEdit.apply(session, new ReviewedEdit.Request(input, turn.reply(),
                        turn.proposalCode(), turn.postconditions(), "eval-" + task.id(), SelectionContext.empty(),
                        () -> true, () -> false, session::isOpen, validator), new ReviewedEdit.Listener() {});
                    if (edit.committed()) committed++;
                    String receipt = edit.execution().feedback();
                    int checksAt = receipt.indexOf("Declared postconditions");
                    edits.add(Map.of("step_ids", List.copyOf(turn.proposalStepIds()), "declared_checks", turn.postconditions().size(),
                        "verified_step_ids", List.copyOf(edit.execution().completedStepIds()),
                        "checks_and_validation", bounded(checksAt < 0 ? "" : receipt.substring(checksAt))));
                    if (!edit.result().codeExecuted || !edit.execution().saved()) {
                        agent.recordOutcome(turn, edit.execution());
                        ending = edit.committed() ? "blocked: committed but not saved" : "blocked: proposal did not apply";
                        detail = bounded(edit.execution().feedback());
                        break requests;
                    }
                    turn = agent.finish(turn, edit.execution(), () -> false, progress -> {});
                }
            } catch (ArcadiaAgent.AgentException failure) {
                turn = failure.turn();
            } catch (RuntimeException failure) {
                ending = "error: " + failure.getClass().getSimpleName();
                break;
            } finally {
                if (turn != null) { requests += turn.requests(); toolCalls += turn.toolCalls(); }
            }
            if (turn != null && !turn.hasProposal()) turn = withTermination(turn, TaskContinuation.afterRequest(turn.termination(), turn.plan(),
                requestProposals, MAX_PROPOSALS));
            var next = TaskContinuation.prompt(turn.plan(), turn.termination());
            if (next.isEmpty() || continuations == MAX_CONTINUATIONS) {
                if (turn.termination() != null) {
                    ending = turn.termination().state().name().toLowerCase(java.util.Locale.ROOT);
                    if (turn.termination().state() != TaskTermination.State.COMPLETE) detail = bounded(turn.termination().reason());
                }
                break;
            }
            continuations++;
            history = turn.messages();
            input = next.get();
        }
        ModelPostconditions.Report checks = ModelPostconditions.check(session, task.checks());
        return new Result(task.id(), checks.allPassed(), checks.markdownSummary(), requests, toolCalls, proposals,
            committed, repairs, continuations, ending, detail, (System.nanoTime() - started) / 1_000_000, List.copyOf(edits));
    }

    private static String bounded(String text) {
        return text == null ? "" : text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private static ArcadiaAgent.Turn withTermination(ArcadiaAgent.Turn turn, TaskTermination termination) {
        return new ArcadiaAgent.Turn(turn.reply(), null, turn.plan(), turn.messages(), turn.requests(), turn.toolCalls(),
            turn.proposalStepIds(), turn.postconditions(), termination);
    }
}
