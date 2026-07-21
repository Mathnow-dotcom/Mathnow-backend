package com.infinityisland.dao;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document("video_ratings")
public class VideoRating {
    @Id
    private String id;
    private String userId;
    private int rating;
    private int level;
    private String beltOrDegree;
    private Date createdAt;

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getBeltOrDegree() { return beltOrDegree; }
    public void setBeltOrDegree(String beltOrDegree) { this.beltOrDegree = beltOrDegree; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}