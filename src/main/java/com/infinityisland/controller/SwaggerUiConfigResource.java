package com.infinityisland.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Swagger UI configuration. Served at /api/swagger-config because of @ApplicationPath("/api").
 * Points Swagger UI at our generated OpenAPI document.
 */
@Path("/swagger-config")
public class SwaggerUiConfigResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> config() {
        return Map.of(
                "urls", List.of(Map.of(
                        "url", "/api/openapi.json",
                        "name", "InfinityIsland API"
                )),
                "deepLinking", true,
                "displayRequestDuration", true,
                "persistAuthorization", true,
                "validatorUrl", "" // disable online validator (optional)
        );
    }
}