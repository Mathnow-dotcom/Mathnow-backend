// ============================================================================
// FILE: ./src/main/java/com/infinityisland/controller/AdminResource.java
// FIXED: Backward compatible - returns array with pagination in headers
// ============================================================================
package com.infinityisland.controller;

import com.infinityisland.dao.DailySummary;
import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.DailySummaryRepository;
import com.infinityisland.repositories.UserRepository;
import com.infinityisland.service.GameConfigService;
import com.infinityisland.service.UserService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {
    private static final Logger log = LoggerFactory.getLogger(AdminResource.class);
    private final DailySummaryRepository daily;

    @Autowired
    UserRepository userRepo;

    @Autowired
    DailySummaryRepository dailyRepo;

    @Autowired
    GameConfigService gameConfigService;

    @Autowired
    UserService userService;

    public AdminResource(DailySummaryRepository daily) {
        this.daily = daily;
    }

    /**
     * POST /api/admin/restore-user
     *
     * Restores a user's progress and stats from admin-provided values.
     * Builds completed levels for each operation and unlocks the next level/operation.
     *
     * Request body:
     * {
     *   "pin": "1234",
     *   "operations": { "add": 19, "sub": 11, "mul": 10 },
     *   "grandTotalCorrect": 15000,
     *   "currentStreak": 5
     * }
     */
    @POST
    @Path("/restore-user")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response restoreUser(@HeaderParam("x-pin") String adminPin, AdminDtos.RestoreUserRequest body) {
        if (!gameConfigService.isValidAdminPin(adminPin)) {
            return Response.status(401)
                    .entity(Map.of("error", "Unauthorized: Invalid Admin PIN"))
                    .build();
        }

        if (body == null || body.pin() == null || body.pin().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "pin is required"))
                    .build();
        }

        if (body.operations() == null || body.operations().isEmpty()) {
            return Response.status(400)
                    .entity(Map.of("error", "operations is required (e.g. {\"add\": 19, \"sub\": 11, \"mul\": 10})"))
                    .build();
        }

        // Normalize operation keys to lowercase
        Map<String, Integer> completedLevels = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : body.operations().entrySet()) {
            completedLevels.put(e.getKey().toLowerCase(), e.getValue());
        }

        Map<String, Object> result = userService.restoreUser(body.pin(), completedLevels,
                body.grandTotalCorrect(), body.currentStreak());

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(result).build();
        } else {
            return Response.status(404).entity(result).build();
        }
    }

    /**
     * GET /api/admin/today-stats?limit=10&offset=0&sort=grandTotalCorrect:desc
     *
     * Query Parameters:
     * - limit: Number of results (max 10, default 10)
     * - offset: Skip N results (default 0)
     * - sort: field:direction for secondary sort within loggedInToday groups
     *         Allowed fields: grandTotalCorrect, todayCorrect, todayActiveMs, name
     *         Allowed directions: asc, desc (default desc)
     *         Default: grandTotalCorrect:desc
     *
     * Sort order is always: loggedInToday desc (primary), then requested sort field.
     *
     * Returns: Array of users (backward compatible)
     * Pagination info in response headers: X-Total-Count, X-Has-More
     */
    @GET
    @Path("/today-stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response todayStats(
            @HeaderParam("x-pin") String pin,
            @QueryParam("limit") @DefaultValue("10") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("sort") @DefaultValue("grandTotalCorrect:desc") String sort) {

        if (!gameConfigService.isValidAdminPin(pin)) {
            return Response.status(401)
                    .entity(Map.of("error", "Unauthorized: Invalid Admin PIN"))
                    .build();
        }

        try {
            // Enforce max limit
            if (limit > 10) limit = 10;
            if (limit < 1) limit = 10;
            if (offset < 0) offset = 0;

            ZoneId pacificZone = ZoneId.of("America/Los_Angeles");
            LocalDate today = ZonedDateTime.now(pacificZone).toLocalDate();

            // 1️⃣ Fetch all users
            List<User> allUsers = userRepo.findAll();
            int totalUsers = allUsers.size();

            List<String> userIds = allUsers.stream()
                    .map(u -> u.getId().toString())
                    .toList();

            // 2️⃣ Bulk query: all summaries for today
            List<DailySummary> todaySummaries = dailyRepo.findByUserIdInAndDate(userIds, today);

            // Map userId -> today summary
            Map<String, DailySummary> todaySummaryMap = todaySummaries.stream()
                    .collect(Collectors.toMap(
                            s -> s.getUserId().toString(),
                            s -> s,
                            (a, b) -> a
                    ));

            // 3️⃣ Build ALL user entries (for sorting)
            List<Map<String, Object>> allEntries = new ArrayList<>();

            for (User user : allUsers) {
                String userId = user.getId().toString();

                DailySummary todayData = todaySummaryMap.get(userId);
                long todayCorrect = (todayData != null) ? todayData.getCorrectCount() : 0;
                long todayActiveMs = (todayData != null) ? todayData.getTotalActiveMs() : 0;
                boolean loggedInToday = today.toString().equals(user.getLastLoginDate());

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("_id", userId);
                entry.put("name", user.getName());
                entry.put("pin", user.getPin());
                entry.put("loggedInToday", loggedInToday);
                entry.put("todayCorrect", todayCorrect);
                entry.put("todayActiveMs", todayActiveMs);
                entry.put("grandTotalCorrect", user.getGrandTotalCorrect() != null ? user.getGrandTotalCorrect() : 0);
                entry.put("currentStreak", user.getCurrentStreak());
                entry.put("lastLoginDate", user.getLastLoginDate());

                allEntries.add(entry);
            }

            // 4️⃣ Parse sort parameter
            String sortField = "grandTotalCorrect";
            boolean sortAsc = false;
            if (sort != null && !sort.isBlank()) {
                String[] parts = sort.split(":");
                String requestedField = parts[0];
                // Whitelist allowed sort fields
                if (Set.of("grandTotalCorrect", "todayCorrect", "todayActiveMs", "name").contains(requestedField)) {
                    sortField = requestedField;
                }
                if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1])) {
                    sortAsc = true;
                }
            }

            // 5️⃣ Sort: loggedInToday desc (always primary), then requested field
            final String sf = sortField;
            final boolean asc = sortAsc;
            allEntries.sort((a, b) -> {
                // Primary: Logged in today (true first)
                boolean aLoggedIn = (Boolean) a.get("loggedInToday");
                boolean bLoggedIn = (Boolean) b.get("loggedInToday");
                if (aLoggedIn != bLoggedIn) {
                    return aLoggedIn ? -1 : 1;
                }

                // Secondary: requested sort field
                int cmp;
                if ("name".equals(sf)) {
                    String aName = (String) a.get("name");
                    String bName = (String) b.get("name");
                    cmp = String.CASE_INSENSITIVE_ORDER.compare(
                            aName != null ? aName : "",
                            bName != null ? bName : "");
                } else {
                    long aVal = ((Number) a.get(sf)).longValue();
                    long bVal = ((Number) b.get(sf)).longValue();
                    cmp = Long.compare(aVal, bVal);
                }
                return asc ? cmp : -cmp;
            });

            // 6️⃣ Apply pagination
            int fromIndex = Math.min(offset, allEntries.size());
            int toIndex = Math.min(offset + limit, allEntries.size());
            List<Map<String, Object>> paginatedResults = allEntries.subList(fromIndex, toIndex);

            // 7️⃣ For paginated results, fetch grand total active time
            List<String> paginatedUserIds = paginatedResults.stream()
                    .map(e -> (String) e.get("_id"))
                    .toList();

            List<DailySummary> paginatedSummaries = dailyRepo.findByUserIdIn(paginatedUserIds);
            Map<String, Long> grandTotalActiveMsMap = paginatedSummaries.stream()
                    .collect(Collectors.groupingBy(
                            s -> s.getUserId().toString(),
                            Collectors.summingLong(DailySummary::getTotalActiveMs)
                    ));

            // Add grand total active time to results
            for (Map<String, Object> entry : paginatedResults) {
                String userId = (String) entry.get("_id");
                entry.put("grandTotalActiveMs", grandTotalActiveMsMap.getOrDefault(userId, 0L));
            }

            // 8️⃣ Return array directly (backward compatible)
            // Put pagination info in response headers
            boolean hasMore = (offset + limit) < totalUsers;

            return Response.ok(paginatedResults)
                    .header("X-Total-Count", totalUsers)
                    .header("X-Offset", offset)
                    .header("X-Limit", limit)
                    .header("X-Has-More", hasMore)
                    .header("X-Sort", sf + ":" + (asc ? "asc" : "desc"))
                    .header("Access-Control-Expose-Headers", "X-Total-Count, X-Offset, X-Limit, X-Has-More, X-Sort")
                    .build();

        } catch (Exception e) {
            log.error("Admin request failed", e);
            return Response.status(500)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        }
    }
}