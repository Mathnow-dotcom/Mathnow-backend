package com.infinityisland.controller;

import com.infinityisland.service.AnalyticsService;
import com.infinityisland.service.PinUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Analytics API for detailed per-fact performance data.
 *
 * Endpoints:
 *   GET /api/analytics/summary           - High-level overview
 *   GET /api/analytics/facts             - Per-fact breakdown with pagination
 *   GET /api/analytics/facts/{op}/{a}/{b} - Single fact detail
 *   GET /api/analytics/struggling        - Facts needing practice
 */
@Component
@Path("/analytics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnalyticsResource {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsResource.class);

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private PinUserResolver pinUserResolver;

    /**
     * GET /api/analytics/summary
     *
     * Returns high-level stats: overall accuracy, breakdown by level & operation.
     */
    @GET
    @Path("/summary")
    public Response getSummary(@HeaderParam("x-pin") String pin) {
        try {
            String userId = pinUserResolver.ensureUserId(pin);
            if (userId == null) {
                return Response.status(401)
                        .entity(Map.of("error", "Invalid or missing PIN"))
                        .build();
            }

            Map<String, Object> summary = analyticsService.getUserSummary(userId);
            return Response.ok(summary).build();

        } catch (Exception e) {
            log.error("Analytics request failed", e);
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * GET /api/analytics/facts?level=1&operation=add&limit=50&offset=0
     *
     * Returns per-fact analytics with filtering and pagination.
     *
     * Query params:
     *   level     - Filter by level (1-7), optional
     *   operation - Filter by operation (add/sub/mul/div), optional
     *   limit     - Max results, default 50, max 100
     *   offset    - Skip N results, default 0
     */
    @GET
    @Path("/facts")
    public Response getFactAnalytics(
            @HeaderParam("x-pin") String pin,
            @QueryParam("level") Integer level,
            @QueryParam("operation") String operation,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset
    ) {
        try {
            String userId = pinUserResolver.ensureUserId(pin);
            if (userId == null) {
                return Response.status(401)
                        .entity(Map.of("error", "Invalid or missing PIN"))
                        .build();
            }

            // Enforce limits
            if (limit > 100) limit = 100;
            if (limit < 1) limit = 50;
            if (offset < 0) offset = 0;

            Map<String, Object> facts = analyticsService.getFactAnalytics(
                    userId, level, operation, limit, offset
            );
            return Response.ok(facts).build();

        } catch (Exception e) {
            log.error("Analytics request failed", e);
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * GET /api/analytics/facts/{operation}/{a}/{b}?limit=20
     *
     * Returns detailed history for a specific fact (e.g., 2+3).
     */
    @GET
    @Path("/facts/{operation}/{a}/{b}")
    public Response getFactDetail(
            @HeaderParam("x-pin") String pin,
            @PathParam("operation") String operation,
            @PathParam("a") Integer a,
            @PathParam("b") Integer b,
            @QueryParam("limit") @DefaultValue("20") int limit
    ) {
        try {
            String userId = pinUserResolver.ensureUserId(pin);
            if (userId == null) {
                return Response.status(401)
                        .entity(Map.of("error", "Invalid or missing PIN"))
                        .build();
            }

            if (operation == null || a == null || b == null) {
                return Response.status(400)
                        .entity(Map.of("error", "operation, a, and b are required"))
                        .build();
            }

            Map<String, Object> detail = analyticsService.getFactDetail(
                    userId, operation, a, b, limit
            );
            return Response.ok(detail).build();

        } catch (Exception e) {
            log.error("Analytics request failed", e);
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * GET /api/analytics/struggling?level=1&limit=10
     *
     * Returns facts where user is struggling (<70% accuracy, 3+ attempts).
     * Useful for targeted practice recommendations.
     */
    @GET
    @Path("/struggling")
    public Response getStrugglingFacts(
            @HeaderParam("x-pin") String pin,
            @QueryParam("level") Integer level,
            @QueryParam("limit") @DefaultValue("10") int limit
    ) {
        try {
            String userId = pinUserResolver.ensureUserId(pin);
            if (userId == null) {
                return Response.status(401)
                        .entity(Map.of("error", "Invalid or missing PIN"))
                        .build();
            }

            Map<String, Object> struggling = analyticsService.getStrugglingFacts(
                    userId, level, limit
            );
            return Response.ok(struggling).build();

        } catch (Exception e) {
            log.error("Analytics request failed", e);
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * GET /api/analytics/level/{level}
     *
     * Convenience endpoint: facts for a specific level.
     */
    @GET
    @Path("/level/{level}")
    public Response getByLevel(
            @HeaderParam("x-pin") String pin,
            @PathParam("level") Integer level,
            @QueryParam("operation") String operation,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset
    ) {
        return getFactAnalytics(pin, level, operation, limit, offset);
    }
}