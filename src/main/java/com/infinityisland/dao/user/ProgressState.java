package com.infinityisland.dao.user;

public class ProgressState {
    private Integer level;
    private Boolean complete;
    private Boolean unlocked;

    public ProgressState() {
        complete = false;
        unlocked = false;
    }

    private BeltStatus white = new BeltStatus();
    private BeltStatus yellow = new BeltStatus();
    private BeltStatus blue = new BeltStatus();
    private BeltStatus green = new BeltStatus();
    private BeltStatus red = new BeltStatus();
    private BeltStatus brown = new BeltStatus();
    private BlackProgress black = new BlackProgress();

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Boolean getComplete() {
        return complete;
    }

    public void setComplete(Boolean complete) {
        this.complete = complete;
    }

    public Boolean getUnlocked() {
        return unlocked;
    }

    public void setUnlocked(Boolean unlocked) {
        this.unlocked = unlocked;
    }

    public BeltStatus getWhite() {
        return white;
    }

    public void setWhite(BeltStatus white) {
        this.white = white;
    }

    public BeltStatus getYellow() {
        return yellow;
    }

    public void setYellow(BeltStatus yellow) {
        this.yellow = yellow;
    }

    public BeltStatus getBlue() {
        return blue;
    }

    public void setBlue(BeltStatus blue) {
        this.blue = blue;
    }

    public BeltStatus getGreen() {
        return green;
    }

    public void setGreen(BeltStatus green) {
        this.green = green;
    }

    public BeltStatus getRed() {
        return red;
    }

    public void setRed(BeltStatus red) {
        this.red = red;
    }

    public BeltStatus getBrown() {
        return brown;
    }

    public void setBrown(BeltStatus brown) {
        this.brown = brown;
    }

    public BlackProgress getBlack() {
        return black;
    }

    public void setBlack(BlackProgress black) {
        this.black = black;
    }
}
