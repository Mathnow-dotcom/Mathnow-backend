package com.infinityisland.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * Redirects several friendly paths to the Swagger UI WebJar,
 * and points it at our OpenAPI config (which in turn points at /api/openapi.json).
 */
@Path("/") // base for the subpaths below
public class SwaggerUiRedirectResource {

    @GET
    @Path("/swaggerui")
    public Response toUiNoDash() {
        return redirect();
    }

    @GET
    @Path("/swagger-ui")
    public Response toUiDash() {
        return redirect();
    }

    @GET
    @Path("/docs")
    public Response toDocs() {
        return redirect();
    }

    private Response redirect() {
        URI ui = URI.create("/webjars/swagger-ui/index.html?configUrl=/api/swagger-config");
        return Response.seeOther(ui).build();
    }

}