package org.polarsys.capella.ai.agent;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capella's own help pages, read from the user's install by allow-listed topic. Nothing is copied
 * into this plug-in: the EPL pages stay in their doc bundles and match the installed Capella.
 */
public final class CapellaHelp {
    public static final String PREFIX = "capella-help";

    private record Page(String bundle, String path, String anchor, String about) {}

    private static final String TH = "org.polarsys.capella.th.doc",
            UI = "org.polarsys.capella.ui.doc",
            TR = "org.polarsys.capella.transitions.doc",
            RE = "org.polarsys.capella.re.doc",
            DG = "org.polarsys.capella.diagrams.doc",
            DATA = "html/Interfaces and Data Modeling/",
            UI_DIAGRAMS = "html/05. Diagram Management/",
            DIAGRAMS = "html/Diagrams/diagrams.html";

    private static final Map<String, Page> TOPICS = new LinkedHashMap<>();

    static {
        topic(
                "data-modeling",
                TH,
                DATA + "Introduction.html",
                null,
                "why and how Capella models data and interfaces");
        topic(
                "data-types",
                TH,
                DATA + "Basic Constructs.html",
                "Simple_Type",
                "simple types: boolean, enumeration, string, numeric, physical quantity");
        topic(
                "classes",
                TH,
                DATA + "Basic Constructs.html",
                "Class",
                "classes, properties, unions and collections");
        topic(
                "associations",
                TH,
                DATA + "Basic Constructs.html",
                "Association",
                "associations between classes, multiplicities, navigability");
        topic(
                "exchange-items",
                TH,
                DATA + "Basic Constructs.html",
                "Communication_Model",
                "exchange items (FLOW, EVENT, OPERATION, SHARED_DATA), their elements and"
                        + " interfaces");
        topic(
                "data-modeling-advanced",
                TH,
                DATA + "Advanced Constructs.html",
                null,
                "advanced data and interface constructs");
        topic(
                "data-modeling-method",
                TH,
                DATA + "Methodological Recommendations.html",
                null,
                "how to organise the data model and interfaces across perspectives");
        topic(
                "fa-to-interfaces",
                TH,
                "html/Functional Analysis to Interface Engineering/Functional Analysis to Interface"
                        + " Engineering.html",
                null,
                "from functional exchanges to exchange items, ports and interfaces");
        topic(
                "state-machines",
                TH,
                "html/Focus on state machines/Focus on state machines.html",
                null,
                "modes and states, regions, transitions, triggers, available functions");
        topic(
                "constraints",
                TH,
                "html/Focus on constraints and opaque expressions/Focus on constraints and opaque"
                        + " expressions.html",
                null,
                "constraints and opaque expressions");
        topic(
                "actors",
                TH,
                "html/Enhancements of the implementation of Actors/Enhancements of the"
                        + " implementation of Actors.html",
                null,
                "how actors are implemented as components");
        topic(
                "functional-chains-physical-paths",
                UI,
                UI_DIAGRAMS + "5.5. Creation of Functional Chains and Physical Paths.html",
                null,
                "functional chains and physical paths");
        topic(
                "function-allocation",
                UI,
                UI_DIAGRAMS + "Allocating Functions on Components.html",
                null,
                "allocating functions to components");
        topic(
                "categories",
                UI,
                "html/07. Modelling Wizards and Accelerators/7.5. Categories Management.html",
                null,
                "exchange, component exchange and physical link categories");
        topic(
                "validation",
                UI,
                "html/12. Model Analysis/12.1. Model Validation.html",
                null,
                "native model validation");
        topic(
                "transitions-concepts",
                TR,
                "html/11. Iterative Transitions/11.1. Concepts.html",
                null,
                "transition concepts between perspectives");
        topic(
                "transitions",
                TR,
                "html/11. Iterative Transitions/11.2. Transitions Between Engineering Phases.html",
                null,
                "OA to SA to LA to PA to EPBS transitions, element by element");
        topic(
                "rec-rpl",
                RE,
                "html/08. Replicable Element (REC-RPL)/8.1. Introduction.html",
                null,
                "replicable elements: REC and RPL");
        topic(
                "rec-rpl-use",
                RE,
                "html/08. Replicable Element (REC-RPL)/8.2. Basic Use Case.html",
                null,
                "creating and instantiating a REC");
        topic(
                "libraries",
                RE,
                "html/09. Libraries/9.1. Introduction.html",
                null,
                "model libraries");
        diagram("oeb", "Operational_Entity_Blank", "Operational Entity Blank (OAB)");
        diagram(
                "oaib",
                "Operational_Activity_Interaction_Blank",
                "Operational Activity Interaction Blank");
        diagram("ocb", "Operational_Capabilities_Blank", "Operational Capabilities Blank");
        diagram("oes", "Operational_Interaction_Scenario", "Operational Interaction Scenario");
        diagram("opd", "Operational_Process_Description", "Operational Process Description");
        diagram("mcb", "Missions_Capabilities_Blank", "Missions Capabilities Blank");
        diagram("sab", "System_Architecture_Blank", "System Architecture Blank");
        diagram("sdfb", "System_Data_Flow_Blank", "System Data Flow Blank");
        diagram("crb", "Capability_Realization_Blank", "Capability Realization Blank");
        diagram("fcd", "Functional_Chain_Description", "Functional Chain Description");
        diagram("fs", "Functional_Scenario", "Functional Scenario");
        diagram("es", "Exchange_Scenario", "Exchange Scenario");
        diagram("is", "Interface_Scenario", "Interface Scenario");
        diagram("msm", "Mode_State_Machine", "Mode State Machine");
        diagram("cdb", "Class_Diagram_Blank", "Class Diagram Blank");
        diagram("idb", "Interfaces_Diagram_Blank", "Interfaces Diagram Blank");
        diagram("lab", "Logical_Architecture_Blank", "Logical Architecture Blank");
        diagram("ldfb", "Logical_Data_Flow_Blank", "Logical Data Flow Blank");
        diagram("pab", "Physical_Architecture_Blank", "Physical Architecture Blank");
        diagram("pdfb", "Physical_Data_Flow_Blank", "Physical Data Flow Blank");
        diagram("ppd", "Physical_Path_Description", "Physical Path Description");
        diagram("epbs", "EPBS_Architecture_Blank", "EPBS Architecture Blank");
    }

    private static void topic(String id, String bundle, String path, String anchor, String about) {
        TOPICS.put(id, new Page(bundle, path, anchor, about));
    }

    private static void diagram(String code, String anchor, String name) {
        topic(
                "diagram-" + code,
                DG,
                DIAGRAMS,
                anchor,
                name + ": palette tools and what each creates");
    }

    private CapellaHelp() {}

    /** The bare prefix (the topic list) and one path per topic. */
    public static Set<String> paths() {
        Set<String> paths = new LinkedHashSet<>();
        paths.add(PREFIX);
        TOPICS.keySet().forEach(id -> paths.add(PREFIX + "/" + id));
        return paths;
    }

    public static String read(String path) throws IOException {
        return read(path, CapellaHelp::open);
    }

    /**
     * The topic list for the bare prefix, else the topic's page as text. Unknown topics are an
     * {@link IllegalArgumentException}; a missing doc bundle is an {@link IllegalStateException}.
     */
    public static String read(String path, BiFunction<String, String, InputStream> opener)
            throws IOException {
        if (PREFIX.equals(path)) {
            StringBuilder list =
                    new StringBuilder(PREFIX + ": Capella's own help in this install, by topic");
            TOPICS.forEach(
                    (id, page) ->
                            list.append('\n')
                                    .append(PREFIX)
                                    .append('/')
                                    .append(id)
                                    .append(": ")
                                    .append(page.about()));
            return list.toString();
        }
        Page page =
                path.startsWith(PREFIX + "/")
                        ? TOPICS.get(path.substring(PREFIX.length() + 1))
                        : null;
        if (page == null)
            throw new IllegalArgumentException(
                    "Unknown Capella help topic. Topics: " + String.join(", ", TOPICS.keySet()));
        try (InputStream input = opener.apply(page.bundle(), page.path())) {
            if (input == null)
                throw new IllegalStateException(
                        "Capella's bundled help is not installed: " + page.bundle());
            return text(
                    new String(input.readNBytes(400_000), StandardCharsets.UTF_8), page.anchor());
        }
    }

    private static InputStream open(String bundle, String entry) {
        Bundle installed = Platform.getBundle(bundle);
        URL url = installed == null ? null : installed.getEntry(entry);
        try {
            return url == null ? null : url.openStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>"),
            ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>"),
            CELL = Pattern.compile("(?is)<t[hd][^>]*>(.*?)</t[hd]>");

    /** Compact text: headings as #, list items as -, table rows as | cells |; images dropped. */
    public static String text(String html, String anchor) {
        String page = html.replaceAll("\\s+", " ");
        if (anchor != null) {
            Matcher start =
                    Pattern.compile("(?i)<h([1-6])[^>]*\\bid=\"" + Pattern.quote(anchor) + "\"")
                            .matcher(page);
            if (start.find()) {
                Matcher end =
                        Pattern.compile("(?i)<h[1-" + start.group(1) + "][\\s>]").matcher(page);
                page =
                        page.substring(
                                start.start(), end.find(start.end()) ? end.start() : page.length());
            }
        }
        page = page.replaceAll("(?is)<(head|script|style)\\b.*?</\\1>", " ");
        page =
                replace(
                        HEADING,
                        page,
                        m ->
                                "\n"
                                        + "#".repeat(Integer.parseInt(m.group(1)))
                                        + " "
                                        + m.group(2)
                                        + "\n");
        page =
                replace(
                        ROW,
                        page,
                        m -> {
                            StringBuilder row = new StringBuilder("\n|");
                            Matcher cell = CELL.matcher(m.group(1));
                            while (cell.find()) row.append(' ').append(cell.group(1)).append(" |");
                            return row.append('\n').toString();
                        });
        page =
                page.replaceAll("(?i)<li[^>]*>", "\n- ")
                        .replaceAll(
                                "(?i)</?(p|div|br|ul|ol|table|dl|dt|dd|pre|blockquote)\\b[^>]*>",
                                "\n")
                        .replaceAll("<[^>]*>", "")
                        .replace("&nbsp;", " ")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&apos;", "'");
        page =
                replace(
                        Pattern.compile("&#(x?)([0-9a-fA-F]+);"),
                        page,
                        m ->
                                new String(
                                        Character.toChars(
                                                Integer.parseInt(
                                                        m.group(2),
                                                        m.group(1).isEmpty() ? 10 : 16))));
        page = page.replace("&amp;", "&");
        StringBuilder out = new StringBuilder();
        for (String line : page.split("\n")) {
            line = line.replaceAll("[ \\t\\u00a0]+", " ").strip();
            if (!line.isEmpty()) out.append(out.length() == 0 ? "" : "\n").append(line);
        }
        return out.toString();
    }

    private static String replace(
            Pattern pattern, String text, java.util.function.Function<Matcher, String> with) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(with.apply(m)));
        return m.appendTail(out).toString();
    }
}
