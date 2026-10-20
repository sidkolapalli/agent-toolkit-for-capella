package org.polarsys.capella.ai.dev;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.polarsys.capella.ai.CodePreviewDialog;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.ai.agent.ArcadiaAgent;
import org.polarsys.capella.ai.agent.ModelVerification;
import org.polarsys.capella.ai.agent.ReviewedEdit;
import org.polarsys.capella.ai.presentation.NativeCheckDescription;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.scripting.GroovyCompilationValidator;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.session.SelectionContext;

/**
 * Model edits proposed by MCP clients. A proposal is compile-checked, then waits for a person to
 * approve it in Capella's review dialog, regardless of the chat's review preference. Approved
 * proposals run once through {@link ReviewedEdit}. Proposals are keyed by session, summary, script
 * and checks, so a client that retries after a lost response gets the same proposal and receipt;
 * the script never runs twice. Status is polled, so slow human review never trips client timeouts.
 */
public final class McpEdits {
    @FunctionalInterface
    public interface Reviewer { boolean approve(Session session, String summary, String code, String checks); }

    private static final long RETENTION_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final int MAX_PROPOSALS = 64;
    /** Replaced only by tests; the default asks a person in Capella. */
    private static Reviewer reviewer = McpEdits::dialog;
    /** Native validation; replaced only by tests that run without the Capella platform. */
    private static java.util.function.Function<Session, ModelVerification.Snapshot> validator = ModelVerification::snapshot;
    private static final Map<String, Proposal> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Proposal> BY_KEY = new LinkedHashMap<>();
    private static ExecutorService worker = newWorker();

    private static final class Proposal {
        final String id, key;
        final long created = System.nanoTime();
        /** queued until its review dialog opens, so a client never waits on a dialog that is not shown yet. */
        volatile String status = "queued";
        volatile Map<String, Object> receipt;
        Proposal(String id, String key) { this.id = id; this.key = key; }
        boolean pending() { return "queued".equals(status) || "awaiting_review".equals(status) || "running".equals(status); }
        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("proposal_id", id);
            view.put("status", status);
            if (receipt != null) view.put("receipt", receipt);
            return view;
        }
    }

    private McpEdits() {}

    static List<Map<String, Object>> tools() {
        Map<String, Object> checks = Map.of("type", "array", "maxItems", 12, "items", Map.of("type", "object",
            "description", "A native check read back after the change: kind element_exists, element_absent or attribute_equals "
                + "with type+name (+layer OA/SA/LA/PA/EPBS) or id; attribute_equals adds attribute and expected."));
        return List.of(
            Map.of("name", "propose_model_edit",
                "description", "Propose a Groovy script (Capella AI api.* bindings, see capella_docs) that changes the open model. "
                    + "Nothing runs until a person approves it in Capella. Returns a proposal_id at once; poll edit_status for the "
                    + "review outcome and the authoritative execution receipt. Proposing the same edit again returns the same proposal.",
                "inputSchema", Map.of("type", "object", "additionalProperties", false, "required", List.of("summary", "script"),
                    "properties", Map.of("summary", Map.of("type", "string", "minLength", 1, "maxLength", 2400),
                        "script", Map.of("type", "string", "minLength", 1, "maxLength", 120_000), "postconditions", checks))),
            Map.of("name", "edit_status",
                "description", "Status of a proposed edit: queued (behind another review), awaiting_review (dialog open in Capella), "
                    + "running, rejected, committed, not_applied or failed. "
                    + "committed includes the receipt: applied, committed, saved, completedStepIds and verification feedback.",
                "inputSchema", Map.of("type", "object", "additionalProperties", false, "required", List.of("proposal_id"),
                    "properties", Map.of("proposal_id", Map.of("type", "string", "maxLength", 80))),
                "annotations", Map.of("readOnlyHint", true)));
    }

    static boolean handles(String tool) { return "propose_model_edit".equals(tool) || "edit_status".equals(tool); }

    /** Returns the tool text; throws IllegalArgumentException for a refused request. */
    static String call(Session session, String tool, Map<String, Object> arguments) {
        if ("edit_status".equals(tool)) {
            Proposal proposal;
            synchronized (BY_ID) { proposal = BY_ID.get(String.valueOf(arguments.get("proposal_id"))); }
            if (proposal == null) throw new IllegalArgumentException("Unknown or expired proposal_id");
            return AgentJson.write(proposal.view());
        }
        if (!LLMPreferences.isToolActionsEnabled()) {
            throw new IllegalArgumentException("Model actions are disabled in Capella's LLM preferences. No change was proposed.");
        }
        String summary = text(arguments, "summary", 2400), code = text(arguments, "script", 120_000);
        List<ArcadiaAgent.Postcondition> checks = postconditions(arguments.get("postconditions"));
        String key = key(session, summary, code, checks);
        synchronized (BY_ID) {
            prune();
            Proposal existing = BY_KEY.get(key);
            if (existing != null) return AgentJson.write(existing.view());
        }
        GroovyCompilationValidator.Report compiled = GroovyExecutor.compileDecoded(code, () -> false, 10_000);
        if (!compiled.success()) {
            throw new IllegalArgumentException("The script did not compile; nothing was proposed.\n" + compiled.formatForRepair());
        }
        Proposal proposal;
        synchronized (BY_ID) {
            Proposal raced = BY_KEY.get(key);
            if (raced != null) return AgentJson.write(raced.view());
            proposal = new Proposal(UUID.randomUUID().toString(), key);
            BY_ID.put(proposal.id, proposal);
            BY_KEY.put(key, proposal);
        }
        worker.execute(() -> run(session, proposal, summary, code, checks));
        Map<String, Object> view = proposal.view();
        view.put("message", "A person must approve this change in Capella before it runs. Poll edit_status with this proposal_id.");
        return AgentJson.write(view);
    }

    private static void run(Session session, Proposal proposal, String summary, String code, List<ArcadiaAgent.Postcondition> checks) {
        try {
            // Always reviewed here, independently of the chat's "review before running" preference.
            proposal.status = "awaiting_review";
            if (!reviewer.approve(session, summary, code, NativeCheckDescription.describeAll(checks))) {
                proposal.status = "rejected";
                return;
            }
            proposal.status = "running";
            ReviewedEdit.Outcome outcome = ReviewedEdit.apply(session, new ReviewedEdit.Request(summary, summary, code, checks,
                "mcp", SelectionContext.empty(), () -> true, () -> false, session::isOpen, validator),
                new ReviewedEdit.Listener() {});
            var execution = outcome.execution();
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("applied", execution.applied());
            receipt.put("committed", execution.committed());
            receipt.put("saved", execution.saved());
            receipt.put("completedStepIds", List.copyOf(execution.completedStepIds()));
            receipt.put("feedback", execution.feedback());
            // Record the receipt before publishing the status, so a poll never sees a final state without it.
            proposal.receipt = receipt;
            proposal.status = outcome.committed() ? "committed" : outcome.attempted() ? "failed" : "not_applied";
        } catch (RuntimeException failure) {
            proposal.receipt = Map.of("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            proposal.status = "failed";
        }
    }

    private static boolean dialog(Session session, String summary, String code, String checks) {
        AtomicBoolean approved = new AtomicBoolean();
        Display.getDefault().syncExec(() -> approved.set(CodePreviewDialog.review(session, code,
            "Proposed by an external MCP client, not by the Capella AI chat.", summary, checks)));
        return approved.get();
    }

    private static List<ArcadiaAgent.Postcondition> postconditions(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> items) || items.size() > 12) throw new IllegalArgumentException("postconditions must be an array of at most 12 checks");
        List<ArcadiaAgent.Postcondition> checks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("kind") instanceof String kind)) throw new IllegalArgumentException("Each postcondition needs a kind");
            Map<String, Object> target = new LinkedHashMap<>();
            map.forEach((field, entry) -> { if (!"kind".equals(field) && !"stepId".equals(field)) target.put(String.valueOf(field), entry); });
            checks.add(new ArcadiaAgent.Postcondition("edit", kind, target));
        }
        return checks;
    }

    private static String text(Map<String, Object> arguments, String name, int maximum) {
        if (!(arguments.get(name) instanceof String value) || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be text of at most " + maximum + " characters");
        }
        return value;
    }

    private static String key(Session session, String summary, String code, List<ArcadiaAgent.Postcondition> checks) {
        String uri = session.getSessionResource() == null ? "" : String.valueOf(session.getSessionResource().getURI());
        String material = uri + '\0' + summary + '\0' + code + '\0' + checks;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void prune() {
        long now = System.nanoTime();
        BY_ID.values().removeIf(old -> {
            boolean expired = now - old.created > RETENTION_NANOS && !old.pending();
            if (expired) BY_KEY.remove(old.key);
            return expired;
        });
        while (BY_ID.size() >= MAX_PROPOSALS) {
            var oldest = BY_ID.values().stream().filter(old -> !old.pending()).findFirst();
            if (oldest.isEmpty()) throw new IllegalArgumentException("Too many proposals are awaiting review in Capella");
            BY_ID.remove(oldest.get().id);
            BY_KEY.remove(oldest.get().key);
        }
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CapellaAI-MCP-Edits");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Forget all proposals; for tests and dev server restarts. */
    public static void reset() {
        synchronized (BY_ID) { BY_ID.clear(); BY_KEY.clear(); }
        worker.shutdownNow();
        worker = newWorker();
    }
}
