package com.sidkolapalli.capella.ai.agent;

import org.eclipse.sirius.business.api.session.Session;
import com.sidkolapalli.capella.ai.ExecutionFeedback;
import com.sidkolapalli.capella.ai.scripting.ExecutionResult;
import com.sidkolapalli.capella.ai.scripting.ScriptRouter;
import com.sidkolapalli.capella.ai.session.SelectionContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * One reviewed model edit, shared by every host: review, run the exact source once in a single
 * transaction, record net changes, read back the declared checks, and compare native validation
 * before and after. A step completes only when the change committed with observed changes, its
 * checks passed and no native error diagnostics were added. Call from a background thread.
 */
public final class ReviewedEdit {
    /** Host presentation hooks, called on the calling thread in this order. */
    public interface Listener {
        default void activity(String text) {}

        /**
         * Right after execution returns, before change capture finishes; hosts record
         * "attempted/applied" here.
         */
        default void ran(ScriptRouter.Result result) {}

        /** Execution facts; {@code changes} is null when no change capture ran. */
        default void executed(
                ScriptRouter.Result result,
                String feedback,
                ModelChangeSummary.ChangeReport changes) {}

        default void checks(String markdown) {}

        default void validation(String markdown) {}
    }

    public record Request(
            String userMessage,
            String summary,
            String code,
            List<ArcadiaAgent.Postcondition> postconditions,
            String conversationId,
            SelectionContext context,
            BooleanSupplier review,
            BooleanSupplier cancelled,
            BooleanSupplier sameModel,
            Function<Session, ModelVerification.Snapshot> validator) {
        public Request {
            postconditions = List.copyOf(postconditions);
        }
    }

    /**
     * {@code committed} includes a commit whose save failed; {@code execution} is the authoritative
     * receipt.
     */
    public record Outcome(
            ScriptRouter.Result result,
            ModelChangeSummary.ChangeReport changes,
            boolean attempted,
            boolean committed,
            boolean changed,
            ArcadiaAgent.ExecutionOutcome execution) {}

    private ReviewedEdit() {}

    public static Outcome apply(Session session, Request request, Listener listener) {
        ModelVerification.Snapshot[] baseline = {null};
        ModelChangeSummary.Recorder[] capture = {null};
        ScriptRouter.Result result;
        ModelChangeSummary.ChangeReport changes = null;
        try {
            result =
                    ScriptRouter.executeProposal(
                            session,
                            request.userMessage(),
                            request.summary(),
                            request.code(),
                            request.conversationId(),
                            request.context(),
                            request.review(),
                            () -> {
                                if (!request.sameModel().getAsBoolean())
                                    throw new CancellationException(
                                            "The target model changed before execution.");
                                listener.activity("Checking the model before applying the change…");
                                baseline[0] = request.validator().apply(session);
                                if (!request.sameModel().getAsBoolean())
                                    throw new CancellationException(
                                            "The target model changed before execution.");
                                capture[0] = ModelChangeSummary.capture(session);
                                listener.activity("Applying the model change…");
                            });
            listener.ran(result);
            if (capture[0] != null) changes = capture[0].finish(result.executionResult);
        } finally {
            if (capture[0] != null) capture[0].close();
        }
        boolean committed =
                result.executionResult != null
                        && (result.executionResult.success()
                                || result.executionResult.code()
                                        == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT);
        boolean changed = committed && changes != null && changes.hasChanges();
        String feedback =
                result.executionResult == null
                        ? "No code was executed. "
                                + (result.error == null
                                        ? actionNote(result.reply, request.summary())
                                        : result.error)
                        : ExecutionFeedback.forHistory(result.executionResult);
        if (changes != null)
            ScriptRouter.registerModelNames(
                    session,
                    request.conversationId(),
                    java.util.stream.Stream.of(
                                    changes.created(), changes.edited(), changes.deleted())
                            .flatMap(List::stream)
                            .map(ModelChangeSummary.ElementChange::name)
                            .toList());
        listener.executed(result, feedback, changes);
        if (changes != null) feedback += "\n" + changes.markdownSummary();
        ModelPostconditions.Report postconditions = null;
        if (committed
                && !request.postconditions().isEmpty()
                && !request.cancelled().getAsBoolean()) {
            listener.activity("Checking the expected model results…");
            postconditions = ModelPostconditions.check(session, request.postconditions());
            String checks = postconditions.markdownSummary();
            feedback += "\n" + checks;
            listener.checks(checks);
        }
        ModelVerification.Comparison verification = null;
        if (committed && (changes == null || !changes.available() || changed)) {
            listener.activity("Checking the applied change with Capella…");
            ModelVerification.Snapshot after =
                    request.cancelled().getAsBoolean()
                            ? ModelVerification.Snapshot.unavailable(
                                    "The request stopped after execution; post-change validation"
                                        + " was not run.")
                            : request.validator().apply(session);
            verification = ModelVerification.compare(baseline[0], after);
            String validationText = verification.markdownSummary();
            feedback += "\n" + validationText;
            listener.validation(validationText);
        }
        Set<String> completed =
                changed
                                && postconditions != null
                                && verification != null
                                && verification.verdict()
                                        == ModelVerification.Verdict.NO_ADDITIONAL_ERROR_DIAGNOSTICS
                        ? postconditions.completedStepIds()
                        : Set.of();
        ArcadiaAgent.ExecutionOutcome outcome =
                new ArcadiaAgent.ExecutionOutcome(
                        changed,
                        committed,
                        committed && result.executionResult.success(),
                        feedback,
                        completed);
        return new Outcome(result, changes, result.attempts > 0, committed, changed, outcome);
    }

    private static String actionNote(String result, String original) {
        return result != null && original != null && result.startsWith(original)
                ? result.substring(original.length()).strip()
                : "The proposal was not run.";
    }
}
