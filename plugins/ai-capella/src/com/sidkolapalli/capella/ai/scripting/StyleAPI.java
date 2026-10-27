package com.sidkolapalli.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import com.sidkolapalli.capella.ai.tools.DiagramCommands;

import java.util.Locale;
import java.util.Map;

/** API for controlling diagram visualization styles (colors, visibility). */
public final class StyleAPI {

    private final Session session;
    private final DiagramAPI diagramAPI;

    // Standard HTML/SVG colors
    private static final Map<String, Integer> COLORS =
            Map.ofEntries(
                    Map.entry("black", 0x000000),
                    Map.entry("white", 0xFFFFFF),
                    Map.entry("red", 0xFF0000),
                    Map.entry("green", 0x008000),
                    Map.entry("blue", 0x0000FF),
                    Map.entry("yellow", 0xFFFF00),
                    Map.entry("cyan", 0x00FFFF),
                    Map.entry("magenta", 0xFF00FF),
                    Map.entry("gray", 0x808080),
                    Map.entry("grey", 0x808080),
                    Map.entry("orange", 0xFFA500),
                    Map.entry("purple", 0x800080),
                    Map.entry("brown", 0xA52A2A),
                    Map.entry("capella_orange", 0xF6AD3C) // Capella functional exchange color
                    );

    public StyleAPI(Session session, DiagramAPI diagramAPI) {
        this.session = session;
        this.diagramAPI = diagramAPI;
    }

    /**
     * Set the color of an element on the last opened diagram.
     *
     * @param element the semantic element
     * @param color name (e.g., "red") or hex code ("#FF0000")
     * @return true if applied
     */
    public boolean setColor(EObject element, String color) {
        return setColor(diagramAPI.lastOpened(), element, color);
    }

    /**
     * Set the color of an element on a specific diagram.
     *
     * @param diagram the diagram
     * @param element the semantic element
     * @param color name (e.g., "red") or hex code ("#FF0000")
     * @return true if applied
     */
    public boolean setColor(DRepresentation diagram, EObject element, String color) {
        Integer rgb = parseColor(color);
        if (rgb == null) {
            throw new IllegalArgumentException("Unknown color: " + color);
        }
        // Set label, background, and border to the same color for high visibility
        // Logic inside DiagramCommands handles checking which features exist
        return DiagramCommands.styleViewElement(session, diagram, element, rgb, null, rgb);
    }

    /** Set the background color of an element. */
    public boolean setBackgroundColor(DRepresentation diagram, EObject element, String color) {
        Integer rgb = parseColor(color);
        return DiagramCommands.styleViewElement(session, diagram, element, null, rgb, null);
    }

    /** Set the label (text) color of an element. */
    public boolean setLabelColor(DRepresentation diagram, EObject element, String color) {
        Integer rgb = parseColor(color);
        return DiagramCommands.styleViewElement(session, diagram, element, rgb, null, null);
    }

    /** Set the border color of an element. */
    public boolean setBorderColor(DRepresentation diagram, EObject element, String color) {
        Integer rgb = parseColor(color);
        return DiagramCommands.styleViewElement(session, diagram, element, null, null, rgb);
    }

    /** Hide an element from the diagram. */
    public boolean hide(DRepresentation diagram, EObject element) {
        if (session == null || diagram == null || element == null) return false;
        // DiagramCommands.removeViewElement actually deletes the view, effectively "hiding" it
        // from the user's perspective in standard Capella usage (un-visualizing it).
        // If we wanted true "Hide" (set visible=false), we'd need a different command.
        // For now, removeViewElement is the safest "Remove from diagram" operation.
        return DiagramCommands.removeViewElement(session, diagram, element);
    }

    /** Show (reveal) an element on the diagram. */
    public boolean show(DRepresentation diagram, EObject element) {
        return diagramAPI.add(diagram, element);
    }

    private Integer parseColor(String color) {
        if (color == null || color.isBlank()) return null;
        String normalized = color.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        // Check named colors
        if (COLORS.containsKey(normalized)) {
            return COLORS.get(normalized);
        }

        // Check Hex
        if (color.startsWith("#")) {
            try {
                return Integer.parseInt(color.substring(1), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
