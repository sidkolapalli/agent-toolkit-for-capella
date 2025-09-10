package org.polarsys.capella.ai.importer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Parses draw.io (.drawio) files into a simple graph of nodes/edges. */
public final class DrawioParser {
    public static class NodeInfo {
        public final String id; public final String label;
        public NodeInfo(String id, String label) { this.id = id; this.label = label; }
    }
    public static class EdgeInfo {
        public final String id; public final String source; public final String target; public final String label;
        public EdgeInfo(String id, String source, String target, String label) { this.id = id; this.source = source; this.target = target; this.label = label; }
    }
    public static class Graph {
        public final List<NodeInfo> nodes = new ArrayList<>();
        public final List<EdgeInfo> edges = new ArrayList<>();
    }

    private DrawioParser() {}

    public static Graph parse(File file) throws Exception {
        String content = Files.readString(file.toPath());
        // Detect mxfile wrapper (compressed)
        if (content.contains("<mxfile") && content.contains("<diagram")) {
            String inner = extractDiagramXml(content);
            return parseMxGraphModel(inner);
        }
        // Assume raw mxGraphModel
        return parseMxGraphModel(content);
    }

    private static String extractDiagramXml(String mxfileXml) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(mxfileXml.getBytes(StandardCharsets.UTF_8)));
        NodeList diagrams = doc.getElementsByTagName("diagram");
        if (diagrams.getLength() == 0) throw new IOException("No <diagram> in mxfile");
        Element d = (Element) diagrams.item(0);
        String compressed = d.getTextContent();
        byte[] base = Base64.getDecoder().decode(compressed);
        Inflater inflater = new Inflater(true); // raw DEFLATE
        InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(base), inflater);
        byte[] buf = iis.readAllBytes();
        String xml = new String(buf, StandardCharsets.UTF_8);
        return xml;
    }

    private static Graph parseMxGraphModel(String xml) throws Exception {
        Graph g = new Graph();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList cells = doc.getElementsByTagName("mxCell");
        for (int i=0; i<cells.getLength(); i++) {
            Element e = (Element) cells.item(i);
            String id = e.getAttribute("id");
            String value = e.getAttribute("value");
            if ("1".equals(e.getAttribute("vertex"))) {
                String label = clean(value);
                g.nodes.add(new NodeInfo(id, label));
            } else if ("1".equals(e.getAttribute("edge"))) {
                String src = e.getAttribute("source");
                String tgt = e.getAttribute("target");
                String label = clean(value);
                g.edges.add(new EdgeInfo(id, src, tgt, label));
            }
        }
        return g;
    }

    private static String clean(String s) {
        if (s == null) return "";
        // draw.io often stores HTML content; strip very simply
        return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}

