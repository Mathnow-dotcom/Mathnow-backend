package com.infinityisland.service;

import com.infinityisland.dao.Catalog;
import com.infinityisland.dao.Catalog.Fact;
import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;
import com.infinityisland.repositories.CatalogRepository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order(0)
public class DivisionCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(DivisionCatalogSeeder.class);

    @Autowired
    private CatalogRepository catalogRepo;

    private static final int[][][] DIV_FACTS = {
            // Level 1: white, yellow, green, blue, red, brown
            { {1,1}, {2,1}, {2,2}, {3,1}, {3,3}, {4,1} },
            // Level 2
            { {4,2}, {4,4}, {5,1}, {5,5}, {6,1}, {6,2} },
            // Level 3
            { {6,3}, {6,6}, {7,1}, {7,7}, {8,1}, {8,2} },
            // Level 4 (Brown intentionally duplicates Blue per curriculum sheet)
            { {8,4}, {8,8}, {9,1}, {9,3}, {9,9}, {9,3} },
            // Level 5
            { {10,2}, {10,5}, {12,2}, {12,3}, {12,4}, {12,6} },
            // Level 6
            { {14,2}, {14,7}, {15,3}, {15,5}, {16,2}, {16,4} },
            // Level 7
            { {16,8}, {18,2}, {18,3}, {18,6}, {18,9}, {21,3} },
            // Level 8
            { {21,7}, {24,3}, {24,4}, {24,6}, {24,8}, {25,5} },
            // Level 9
            { {27,3}, {27,9}, {28,4}, {28,7}, {30,5}, {30,6} },
            // Level 10
            { {32,4}, {32,8}, {36,6}, {40,5}, {40,8}, {42,6} },
            // Level 11
            { {42,7}, {45,5}, {45,9}, {48,6}, {48,8}, {54,6} },
            // Level 12
            { {54,9}, {56,7}, {56,8}, {63,7}, {63,9}, {64,8} },
            // Level 13
            { {72,8}, {72,9}, {81,9}, {72,8}, {72,9}, {81,9} },
    };

    @PostConstruct
    public void seedIfMissing() {
        String op = Operation.DIV.value();
        List<String> belts = Belt.COLORED_ORDER;
        int inserted = 0;

        for (int level = 1; level <= DIV_FACTS.length; level++) {
            for (int b = 0; b < belts.size(); b++) {
                String belt = belts.get(b);
                if (catalogRepo.findByOperationAndLevelAndBelt(op, String.valueOf(level), belt).isPresent()) {
                    continue;
                }
                int[] pair = DIV_FACTS[level - 1][b];
                validateDivisionFact(pair[0], pair[1]);

                Fact fact = new Fact();
                fact.setA(pair[0]);
                fact.setB(pair[1]);
                fact.setIdentical(pair[0] == pair[1]);

                Catalog cat = new Catalog();
                cat.setOperation(op);
                cat.setLevel(String.valueOf(level));
                cat.setBelt(belt);
                List<Fact> facts = new ArrayList<>();
                facts.add(fact);
                cat.setFacts(facts);

                catalogRepo.save(cat);
                inserted++;
            }
        }

        if (inserted > 0) {
            log.info("[INIT] Seeded {} division catalog documents", inserted);
        }
    }

    static void validateDivisionFact(int a, int b) {
        if (b == 0) {
            throw new IllegalStateException("Division fact rejected: divisor cannot be 0 (a=" + a + ")");
        }
        if (a % b != 0) {
            throw new IllegalStateException("Division fact rejected: non-integer result (a=" + a + ", b=" + b + ")");
        }
    }
}
