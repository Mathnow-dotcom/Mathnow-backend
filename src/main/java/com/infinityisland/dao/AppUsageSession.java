package com.infinityisland.dao;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A single server-side usage checkpoint stream per user. */
@Document("app_usage_sessions")
public class AppUsageSession {
    @Id
    private String id;
    private String userId;
    private boolean active;
    private Instant lastAccountedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getLastAccountedAt() { return lastAccountedAt; }
    public void setLastAccountedAt(Instant lastAccountedAt) { this.lastAccountedAt = lastAccountedAt; }
}
