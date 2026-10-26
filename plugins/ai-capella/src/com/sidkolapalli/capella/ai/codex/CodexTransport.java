package com.sidkolapalli.capella.ai.codex;

import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bidirectional app-server wire connection. Event consumers own tools and approvals. */
public interface CodexTransport extends AutoCloseable {
    Map<String, Object> request(
            String method, Map<String, Object> params, BooleanSupplier cancelled) throws Exception;

    void notify(String method, Map<String, Object> params) throws Exception;

    /** Returns null when the timeout expires without an event. */
    Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout) throws Exception;

    void respond(Object requestId, Map<String, Object> result) throws Exception;

    void reject(Object requestId, int code, String message) throws Exception;

    @Override
    void close();
}
