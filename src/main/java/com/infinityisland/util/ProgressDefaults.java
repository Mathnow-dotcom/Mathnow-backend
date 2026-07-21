package com.infinityisland.util;

import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;

import java.util.*;

public final class ProgressDefaults {

    private ProgressDefaults() {}

    public static Map<String, Object> baselineProgress() {
        Map<String, Object> progress = new LinkedHashMap<>();

        Map<String, Object> addNode = new LinkedHashMap<>();
        addNode.put("L1", createLevelNode(1, true));
        progress.put(Operation.ADD.value(), addNode);

        Map<String, Object> subNode = new LinkedHashMap<>();
        subNode.put("L1", createLevelNode(1, false));
        progress.put(Operation.SUB.value(), subNode);

        Map<String, Object> mulNode = new LinkedHashMap<>();
        mulNode.put("L1", createLevelNode(1, false));
        progress.put(Operation.MUL.value(), mulNode);

        Map<String, Object> divNode = new LinkedHashMap<>();
        divNode.put("L1", createLevelNode(1, false));
        progress.put(Operation.DIV.value(), divNode);

        return progress;
    }

    public static Map<String, Object> createLevelNode(int level, boolean unlocked) {
        Map<String, Object> lvl = new LinkedHashMap<>();
        lvl.put("level", level);
        lvl.put("unlocked", unlocked);
        lvl.put("completed", false);

        lvl.put(Belt.WHITE.value(), belt(false, unlocked));
        lvl.put(Belt.YELLOW.value(), belt(false, false));
        lvl.put(Belt.GREEN.value(), belt(false, false));
        lvl.put(Belt.BLUE.value(), belt(false, false));
        lvl.put(Belt.RED.value(), belt(false, false));
        lvl.put(Belt.BROWN.value(), belt(false, false));

        Map<String, Object> black = new HashMap<>();
        black.put("unlocked", false);
        black.put("completedDegrees", new ArrayList<Integer>());
        lvl.put("black", black);

        Map<String, Object> pretest = new HashMap<>();
        pretest.put("taken", false);
        pretest.put("passed", false);
        lvl.put("pretest", pretest);

        return lvl;
    }

    public static Map<String, Object> belt(boolean completed, boolean unlocked) {
        Map<String, Object> m = new HashMap<>();
        m.put("completed", completed);
        m.put("unlocked", unlocked);
        return m;
    }
}
