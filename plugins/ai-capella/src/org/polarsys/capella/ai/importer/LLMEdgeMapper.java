package org.polarsys.capella.ai.importer;

import java.util.HashMap;
import java.util.Map;

import org.polarsys.capella.ai.importer.DrawioParser.Graph;
import org.polarsys.capella.ai.llm.LLMService;

/** Uses LLM to classify draw.io edges (functional vs component). */
public final class LLMEdgeMapper {
    private LLMEdgeMapper() {}

    public static Map<String, String> classifyEdges(Graph g) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("You are classifying edges from a draw.io graph into 'functional' or 'component'.\\n");
        sb.append("Return a JSON object mapping edge_id to type, no extra text. Example: {\"e1\":\"functional\",\"e2\":\"component\"}.\\n");
        sb.append("Nodes (id: label):\\n");
        for (var n : g.nodes) sb.append(n.id).append(": ").append(n.label).append("\\n");
        sb.append("Edges (id: source->target label):\\n");
        for (var e : g.edges) sb.append(e.id).append(": ").append(e.source).append("->").append(e.target).append(" ").append(e.label).append("\\n");
        String reply = LLMService.get().send(sb.toString());
        return parseSimpleJsonMap(reply);
    }

    // Very simple JSON parser for {"id":"type",...}
    private static Map<String, String> parseSimpleJsonMap(String s) {
        Map<String, String> out = new HashMap<>();
        if (s == null) return out;
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) return out;
        String inner = s.substring(b + 1, e);
        String[] parts = inner.split(",");
        for (String p : parts) {
            String[] kv = p.split(":");
            if (kv.length >= 2) {
                String k = unq(kv[0]);
                String v = unq(kv[1]).toLowerCase();
                if (v.contains("func")) v = "functional"; else v = "component";
                if (!k.isBlank()) out.put(k, v);
            }
        }
        return out;
    }

    private static String unq(String s) {
        String t = s.trim();
        if (t.startsWith("\"")) t = t.substring(1);
        if (t.endsWith("\"")) t = t.substring(0, t.length()-1);
        return t;
    }
}

