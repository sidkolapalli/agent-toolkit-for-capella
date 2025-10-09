package org.polarsys.capella.ai.importer;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.llm.ToolRouter;
import org.polarsys.capella.ai.llm.ToolRouter.Result;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.model.ModelSummary;

/**
 * Spec-driven execution: import natural language requirements/spec text and request a TOOL_PLAN.
 */
public final class SpecImporter {
    private SpecImporter() {}

    public static ToolPlan planFromSpec(Session session, String specText) {
        if (session == null || specText == null || specText.isBlank()) return null;
        List<LLMService.Message> messages = new ArrayList<>();
        messages.add(LLMService.Message.system(ToolRouter.buildSystemPromptForExternal(session)));
        StringBuilder user = new StringBuilder();
        user.append("From the following specification, materialize an initial OA→PA skeleton with relevant diagrams.\\n");
        user.append("Respond with a single TOOL_PLAN only. Use view actions for diagrams, and structural actions for semantics.\\n");
        user.append("Specification:\n").append(specText).append("\n");
        user.append("Current model snapshot:\n").append(ModelSummary.summarize(session));
        messages.add(LLMService.Message.user(user.toString()));
        try {
            String reply = LLMService.get().send(messages);
            Result parsed = ToolRouter.parseReplyForExternal(session, reply);
            return parsed != null ? parsed.plan : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean applyPlan(Session session, DRepresentation rep, ToolPlan plan) {
        if (plan == null || plan.isEmpty()) return false;
        ToolRouter.PlanExecutionResult r = ToolRouter.applyPlan(session, rep, plan);
        return r != null && r.errors.isEmpty();
    }
}

