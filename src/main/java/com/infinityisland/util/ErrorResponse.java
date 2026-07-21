package com.infinityisland.util;

import jakarta.ws.rs.core.Response;
import java.util.Map;

public final class ErrorResponse {

    private ErrorResponse() {}

    public static Map<String, Object> of(String message) {
        return Map.of("error", Map.of("message", message != null ? message : "Unknown error occurred"));
    }

    public static Map<String, Object> of(String message, String code) {
        return Map.of("error", Map.of("message", message, "code", code));
    }

    public static Map<String, Object> simple(String message) {
        return Map.of("error", message);
    }

    public static Response respond(Response.Status status, String message) {
        return Response.status(status).entity(of(message)).build();
    }
}
