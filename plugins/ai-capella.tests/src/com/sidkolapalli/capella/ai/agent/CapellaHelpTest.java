package com.sidkolapalli.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class CapellaHelpTest {
    private static final String PAGE =
            "<?xml version='1.0' encoding='utf-8' ?><html><head><title>Ignored</title>"
                    + "<style>p{}</style></head><body>"
                    + "<h1 id=\"Guide\">Guide</h1><p>Intro &amp; scope&nbsp;here.</p>"
                    + "<h2 id=\"First\">First</h2><p>One\n   two</p><img src=\"a.png\"/>"
                    + "<ul><li>alpha</li><li>beta &lt;b&gt;</li></ul>"
                    + "<h3 id=\"Inner\">Inner</h3><table><tr><th>Name</th><th>Kind</th></tr>"
                    + "<tr><td>Port</td><td>FLOW</td></tr></table>"
                    + "<h2 id=\"Second\">Second</h2><p>Later</p><script>x()</script></body></html>";

    @Test
    public void helpHtmlBecomesCompactTextWithHeadingsListsAndTables() {
        assertEquals(
                "# Guide\nIntro & scope here.\n## First\nOne two\n- alpha\n- beta <b>\n### Inner\n"
                        + "| Name | Kind |\n| Port | FLOW |\n## Second\nLater",
                CapellaHelp.text(PAGE, null));
    }

    @Test
    public void anAnchorKeepsOnlyThatSectionAndItsSubsections() {
        assertEquals(
                "## First\n"
                    + "One two\n"
                    + "- alpha\n"
                    + "- beta <b>\n"
                    + "### Inner\n"
                    + "| Name | Kind |\n"
                    + "| Port | FLOW |",
                CapellaHelp.text(PAGE, "First"));
    }

    @Test
    public void everyTopicIsAShortIdUnderTheHelpPrefix() {
        assertTrue(CapellaHelp.paths().contains("capella-help"));
        assertTrue(CapellaHelp.paths().contains("capella-help/state-machines"));
        assertTrue(CapellaHelp.paths().size() > 25);
        for (String path : CapellaHelp.paths())
            assertTrue(path, path.matches("capella-help(/[a-z0-9-]+)?"));
    }

    @Test
    public void theBarePrefixListsEveryTopicWithoutOpeningABundle() throws Exception {
        String list = CapellaHelp.read("capella-help", (bundle, entry) -> fail());
        assertTrue(list, list.contains("capella-help/state-machines: "));
        assertEquals(CapellaHelp.paths().size(), list.split("\n").length);
    }

    @Test
    public void aTopicReadsItsAllowListedPageFromItsBundle() throws Exception {
        List<String> opened = new ArrayList<>();
        BiFunction<String, String, InputStream> opener =
                (bundle, entry) -> {
                    opened.add(bundle + "!" + entry);
                    return new ByteArrayInputStream(PAGE.getBytes(StandardCharsets.UTF_8));
                };
        String text = CapellaHelp.read("capella-help/state-machines", opener);
        assertEquals(
                List.of(
                        "org.polarsys.capella.th.doc!html/Focus on state machines/Focus on state"
                                + " machines.html"),
                opened);
        assertTrue(text, text.startsWith("# Guide"));
    }

    @Test
    public void unknownTopicsAndPathTricksAreRejectedWithTheValidIds() {
        for (String path :
                List.of(
                        "capella-help/nope",
                        "capella-help/../api/create.md",
                        "capella-help/state-machines/../x",
                        "capella-help/")) {
            IllegalArgumentException error =
                    assertThrows(
                            path,
                            IllegalArgumentException.class,
                            () -> CapellaHelp.read(path, (bundle, entry) -> fail()));
            assertTrue(error.getMessage(), error.getMessage().contains("state-machines"));
        }
    }

    @Test
    public void aMissingBundleSaysTheHelpIsNotInstalled() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                CapellaHelp.read(
                                        "capella-help/transitions", (bundle, entry) -> null));
        assertTrue(error.getMessage(), error.getMessage().contains("not installed"));
    }

    private static InputStream fail() {
        throw new AssertionError("no bundle may be opened");
    }
}
