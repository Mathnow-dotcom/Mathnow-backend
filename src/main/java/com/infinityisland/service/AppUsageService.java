package com.infinityisland.service;

import com.infinityisland.dao.AppUsageSession;
import com.infinityisland.dao.DailySummary;
import com.infinityisland.repositories.AppUsageSessionRepository;
import com.infinityisland.repositories.DailySummaryRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.UUID;

/**
 * Persists app presence independently from quiz timing. A single active session per
 * user makes concurrent tabs and retried checkpoints idempotent at the server.
 */
@Service
public class AppUsageService {
    private static final ZoneId APP_ZONE = ZoneId.of("America/Los_Angeles");
    // Browser heartbeats run every 15 seconds. Larger gaps mean the tab/browser
    // was suspended or a prior session was not cleanly closed, not active usage.
    private static final Duration MAX_UNCHECKPOINTED_USAGE = Duration.ofSeconds(45);

    public record Usage(String sessionId, String date, long todayUsageMs, long lifetimeUsageMs) {}

    private final AppUsageSessionRepository sessions;
    private final DailySummaryRepository daily;
    private final MongoTemplate mongo;

    public AppUsageService(AppUsageSessionRepository sessions, DailySummaryRepository daily, MongoTemplate mongo) {
        this.sessions = sessions;
        this.daily = daily;
        this.mongo = mongo;
    }

    public synchronized Usage start(String userId) {
        AppUsageSession session = sessions.findFirstByUserIdAndActiveTrue(userId).orElse(null);
        if (session != null && isStale(session, Instant.now())) {
            // Never settle a stale session: doing so would count time while the
            // app was closed, refreshed, or suspended.
            session.setActive(false);
            sessions.save(session);
            session = null;
        }
        if (session == null) {
            AppUsageSession created = new AppUsageSession();
            created.setId(UUID.randomUUID().toString());
            created.setUserId(userId);
            created.setActive(true);
            created.setLastAccountedAt(Instant.now());
            try {
                session = sessions.save(created);
            } catch (DuplicateKeyException ignored) {
                // A concurrent login in another application instance created it.
                session = sessions.findFirstByUserIdAndActiveTrue(userId)
                        .orElseThrow(() -> new IllegalStateException("Unable to start usage session"));
            }
        }
        return totals(userId, session.getId());
    }

    public synchronized Usage checkpoint(String userId, String sessionId, boolean resetElapsed) {
        AppUsageSession session = requireActiveSession(userId, sessionId);
        Instant now = Instant.now();
        if (resetElapsed) {
            // The browser became visible again. Restart from this point so time
            // spent in a background tab cannot be credited as app usage.
            session.setLastAccountedAt(now);
            sessions.save(session);
        } else {
            settle(session, now);
        }
        return totals(userId, session.getId());
    }

    public synchronized Usage stop(String userId, String sessionId, Long inactiveDurationMs) {
        AppUsageSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElse(null);
        if (session == null) return totals(userId, null);
        if (session.isActive()) {
            settle(session, Instant.now());
            if (inactiveDurationMs != null && inactiveDurationMs > 0 && !session.isInactivityDeductionApplied()) {
                // Persist the guard before changing totals, so a retried automatic
                // logout cannot deduct the same inactivity period twice.
                session.setInactivityDeductionApplied(true);
                sessions.save(session);
                deductToday(session.getUserId(), inactiveDurationMs);
            }
            session.setActive(false);
            sessions.save(session);
        }
        return totals(userId, session.getId());
    }

    public Usage getTotals(String userId) {
        return totals(userId, null);
    }

    private AppUsageSession requireActiveSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Usage session required");
        return sessions.findById(sessionId)
                .filter(s -> s.isActive() && s.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Usage session is not active"));
    }

    /** Split elapsed time at midnight so each calendar day's total remains exact. */
    private void settle(AppUsageSession session, Instant now) {
        Instant cursor = session.getLastAccountedAt();
        if (cursor == null || !now.isAfter(cursor)) return;
        if (Duration.between(cursor, now).compareTo(MAX_UNCHECKPOINTED_USAGE) > 0) {
            session.setLastAccountedAt(now);
            sessions.save(session);
            return;
        }
        while (cursor.isBefore(now)) {
            ZonedDateTime local = cursor.atZone(APP_ZONE);
            Instant boundary = local.toLocalDate().plusDays(1).atStartOfDay(APP_ZONE).toInstant();
            Instant end = now.isBefore(boundary) ? now : boundary;
            long elapsed = Duration.between(cursor, end).toMillis();
            if (elapsed > 0) increment(local.toLocalDate(), session.getUserId(), elapsed);
            cursor = end;
        }
        session.setLastAccountedAt(now);
        sessions.save(session);
    }

    private boolean isStale(AppUsageSession session, Instant now) {
        Instant lastAccountedAt = session.getLastAccountedAt();
        return lastAccountedAt == null ||
                Duration.between(lastAccountedAt, now).compareTo(MAX_UNCHECKPOINTED_USAGE) > 0;
    }

    private void increment(LocalDate date, String userId, long elapsedMs) {
        Query query = new Query(Criteria.where("userId").is(userId).and("date").is(date));
        Update update = new Update().setOnInsert("userId", userId).setOnInsert("date", date).inc("appUsageMs", elapsedMs);
        mongo.upsert(query, update, DailySummary.class);
    }

    private void deductToday(String userId, long durationMs) {
        LocalDate today = LocalDate.now(APP_ZONE);
        Query query = new Query(Criteria.where("userId").is(userId).and("date").is(today));
        mongo.updateFirst(query, new Update().inc("appUsageMs", -durationMs), DailySummary.class);
    }

    private Usage totals(String userId, String sessionId) {
        LocalDate today = LocalDate.now(APP_ZONE);
        long todayMs = daily.findByUserIdAndDate(userId, today).map(DailySummary::getAppUsageMs).orElse(0L);
        long lifetimeMs = daily.findByUserId(userId).stream().mapToLong(DailySummary::getAppUsageMs).sum();
        return new Usage(sessionId, today.toString(), todayMs, lifetimeMs);
    }
}
