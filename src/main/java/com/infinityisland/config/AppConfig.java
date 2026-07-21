package com.infinityisland.config;

import com.infinityisland.controller.*;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.stereotype.Component;
import jakarta.ws.rs.ApplicationPath;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

import java.util.List;
import java.util.Set;

@Component
@ApplicationPath("/api")
public class AppConfig extends ResourceConfig {
    public AppConfig() {
        // --- Your JAX-RS resources/filters/providers ---
        register(UserResource.class);
        register(AuthResource.class);
        register(QuizResource.class);
        register(AdminResource.class);
        register(AnalyticsResource.class);
        register(ConfigResource.class);
        register(CacheStatsResource.class);
        register(CorsFilter.class);
        register(com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider.class);

        // --- OpenAPI generation ---
        OpenApiResource openApi = new OpenApiResource();
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("InfinityIsland API")
                        .version("1.0.0"));
        openAPI.addServersItem(new Server().url("/"));
        openAPI.components(new Components().addSecuritySchemes(
                "pinAuth",
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("x-pin")
                        .description("user pin sent via x-pin header")
        ));
        openAPI.addSecurityItem(new SecurityRequirement().addList("pinAuth"));
        SwaggerConfiguration oasConfig = new SwaggerConfiguration()
                .prettyPrint(true)
                .cacheTTL(0L)
                .resourcePackages(Set.of("com.infinityisland.controller"))
                .openAPI(openAPI);
        openApi.setOpenApiConfiguration(oasConfig);
        register(openApi);

        // --- Swagger UI plumbing ---
        register(SwaggerUiRedirectResource.class);
        register(SwaggerUiConfigResource.class);
    }
}