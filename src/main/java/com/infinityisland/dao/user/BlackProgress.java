package com.infinityisland.dao.user;

import java.util.ArrayList;
import java.util.List;

public class BlackProgress {
    private Boolean unlocked;
    private List<Integer> completedDegrees;

    public BlackProgress() {
        unlocked = false;
        completedDegrees = new ArrayList<>();
    }

    public Boolean getUnlocked() {
        return unlocked;
    }

    public void setUnlocked(Boolean unlocked) {
        this.unlocked = unlocked;
    }

    public List<Integer> getCompletedDegrees() {
        return completedDegrees;
    }

    public void setCompletedDegrees(List<Integer> completedDegrees) {
        this.completedDegrees = completedDegrees;
    }
}
