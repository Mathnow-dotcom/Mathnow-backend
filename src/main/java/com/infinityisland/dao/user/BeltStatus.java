package com.infinityisland.dao.user;

public class BeltStatus {
    private Boolean completed;
    private Boolean unlocked;

    public BeltStatus() {
        completed = false;
        unlocked = false;
    }

    public Boolean getCompleted() {
        return completed;
    }

    public void setCompleted(Boolean completed) {
        this.completed = completed;
    }

    public Boolean getUnlocked() {
        return unlocked;
    }

    public void setUnlocked(Boolean unlocked) {
        this.unlocked = unlocked;
    }
}
