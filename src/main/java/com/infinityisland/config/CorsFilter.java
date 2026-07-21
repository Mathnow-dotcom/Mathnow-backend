package com.infinityisland.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(0) // run before other filters
public class CorsFilter implements Filter {

    // configure in application.yml (comma-separated)
    private Set<String> allowedOrigins;

    // Hardcode or read from env/config
    private String configured = System.getProperty(
            "app.cors.allowed-origins",
            System.getenv().getOrDefault("APP_CORS_ALLOWED_ORIGINS",
                    "http://localhost:5173,http://127.0.0.1:5173")
    );

    @PostConstruct
    void init() {
        allowedOrigins = Arrays.stream(configured.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String origin = request.getHeader("Origin");
        boolean allow = origin != null && (allowedOrigins.contains("*") || allowedOrigins.contains(origin));

        if (allow) {
            response.setHeader("Access-Control-Allow-Origin", origin); // echo origin
            response.setHeader("Vary", "Origin");
            // Only set this true if you actually use cookies/auth headers; harmless otherwise
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
            // MUST include custom headers your UI sends (x-pin, Content-Type)
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, x-pin, Authorization");
            response.setHeader("Access-Control-Max-Age", "86400");
        }

        // Short-circuit preflight
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        chain.doFilter(req, res);
    }
}