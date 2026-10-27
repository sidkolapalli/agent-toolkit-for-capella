package com.sidkolapalli.capella.ai.dev;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.agent.AgentJson;
import com.sidkolapalli.capella.ai.agent.ArcadiaModelTools;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.ctx.CtxFactory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Model Context Protocol over the dev server: any MCP client can read the open Capella model. */
public class McpServerTest {
    private Resource semantic;
    private TransactionalEditingDomain domain;
    private Session session;
    private final AtomicInteger saves = new AtomicInteger();

    @Before
    public void createModel() {
        Display.getDefault();
        var resources = new ResourceSetImpl();
        resources
                .getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        semantic = resources.createResource(URI.createURI("memory:/mcp-model.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setName("MCP project");
        semantic.getContents().add(project);
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        var architecture = CtxFactory.eINSTANCE.createSystemAnalysis();
        architecture.setName("SA");
        engineering.getOwnedArchitectures().add(architecture);
        var functions = CtxFactory.eINSTANCE.createSystemFunctionPkg();
        architecture.setOwnedFunctionPkg(functions);
        var function = CtxFactory.eINSTANCE.createSystemFunction();
        function.setId("mcp-function");
        function.setName("Monitor battery");
        functions.getOwnedSystemFunctions().add(function);
        semantic.setTrackingModification(true);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "isOpen" -> true;
                                            case "getSemanticResources" -> List.of(semantic);
                                            case "getSessionResource" -> semantic;
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "save" -> {
                                                saves.incrementAndGet();
                                                throw new AssertionError(
                                                        "MCP reads must never save");
                                            }
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            default -> null;
                                        });
    }

    @After
    public void closeModel() {
        if (domain != null) domain.dispose();
    }

    @Test
    public void initializeAdvertisesToolsAndEchoesTheRequestId() {
        Map<?, ?> response =
                call(
                        session,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                7,
                                "method",
                                "initialize",
                                "params",
                                Map.of(
                                        "protocolVersion",
                                        "2025-06-18",
                                        "capabilities",
                                        Map.of(),
                                        "clientInfo",
                                        Map.of("name", "test", "version", "1"))));
        assertEquals(7, ((Number) response.get("id")).intValue());
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertTrue(((Map<?, ?>) result.get("capabilities")).containsKey("tools"));
        assertEquals("capella-ai", ((Map<?, ?>) result.get("serverInfo")).get("name"));
        assertNull(
                "Notifications get no response",
                McpServer.handle(
                        session,
                        AgentJson.write(
                                Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))));
    }

    @Test
    public void toolsListOffersEveryNativeReadToolWithItsSchema() {
        List<?> tools =
                (List<?>)
                        ((Map<?, ?>) call(session, request("tools/list", Map.of())).get("result"))
                                .get("tools");
        var names = tools.stream().map(tool -> ((Map<?, ?>) tool).get("name")).toList();
        assertTrue(names.containsAll(ArcadiaModelTools.toolNames()));
        for (Object tool : tools) {
            Map<?, ?> spec = (Map<?, ?>) tool;
            assertTrue(spec.get("description") instanceof String);
            assertEquals("object", ((Map<?, ?>) spec.get("inputSchema")).get("type"));
            assertFalse("Codex-only fields stay out of MCP", spec.containsKey("type"));
        }
    }

    @Test
    public void readToolCallsReturnModelDataWithoutChangingTheModel() {
        semantic.setModified(false);
        Map<?, ?> result =
                (Map<?, ?>)
                        call(
                                        session,
                                        request(
                                                "tools/call",
                                                Map.of(
                                                        "name",
                                                        "model_search",
                                                        "arguments",
                                                        Map.of("query", "battery"))))
                                .get("result");
        assertEquals(Boolean.FALSE, result.get("isError"));
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        assertTrue(text, text.contains("mcp-function"));
        assertFalse(semantic.isModified());
        assertEquals(0, saves.get());
    }

    @Test
    public void toolErrorsAreResultsAndProtocolErrorsAreJsonRpcErrors() {
        Map<?, ?> bad =
                (Map<?, ?>)
                        call(
                                        session,
                                        request(
                                                "tools/call",
                                                Map.of(
                                                        "name",
                                                        "model_inspect",
                                                        "arguments",
                                                        Map.of("code", "eSet(...)"))))
                                .get("result");
        assertEquals(Boolean.TRUE, bad.get("isError"));
        assertEquals(
                -32602,
                errorCode(
                        call(
                                session,
                                request(
                                        "tools/call",
                                        Map.of("name", "execute_groovy", "arguments", Map.of())))));
        assertEquals(-32601, errorCode(call(session, request("resources/list", Map.of()))));
        assertEquals(
                -32700,
                errorCode(
                        (Map<?, ?>)
                                AgentJson.parse(McpServer.handle(session, "{not json"), 100_000)));
        assertEquals(
                -32600,
                errorCode((Map<?, ?>) AgentJson.parse(McpServer.handle(session, "[]"), 100_000)));
        Map<?, ?> closed =
                (Map<?, ?>)
                        call(
                                        null,
                                        request(
                                                "tools/call",
                                                Map.of(
                                                        "name",
                                                        "model_summary",
                                                        "arguments",
                                                        Map.of())))
                                .get("result");
        assertEquals(Boolean.TRUE, closed.get("isError"));
    }

    private static Map<String, Object> request(String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", "r1", "method", method, "params", params);
    }

    private static Map<?, ?> call(Session session, Map<String, Object> message) {
        return (Map<?, ?>)
                AgentJson.parse(McpServer.handle(session, AgentJson.write(message)), 200_000);
    }

    private static int errorCode(Map<?, ?> response) {
        return ((Number) ((Map<?, ?>) response.get("error")).get("code")).intValue();
    }
}
