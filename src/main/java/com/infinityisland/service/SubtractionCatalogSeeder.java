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
import java.util.Objects;
import java.util.Optional;

@Component("subtractionCatalogSeeder")
@Order(0)
public class SubtractionCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(SubtractionCatalogSeeder.class);

    @Autowired
    private CatalogRepository catalogRepo;

    // Canonical subtraction layout: 11 levels x 6 colored belts.
    private static final int[][][] SUB_FACTS = {
            { {0,0}, {1,0}, {2,0}, {3,0}, {4,0}, {5,0} },
            { {1,1}, {2,1}, {3,1}, {4,1}, {5,1}, {2,2} },
            { {2,1}, {4,2}, {5,2}, {3,3}, {4,3}, {5,3} },
            { {4,4}, {5,4}, {5,5}, {6,0}, {7,0}, {8,0} },
            { {9,0}, {10,0}, {6,1}, {7,1}, {8,1}, {9,1} },
            { {10,1}, {6,2}, {7,2}, {8,2}, {9,2}, {10,2} },
            { {6,3}, {7,3}, {8,3}, {9,3}, {10,3}, {6,4} },
            { {7,4}, {8,4}, {9,4}, {10,4}, {6,5}, {7,5} },
            { {8,5}, {9,5}, {10,5}, {6,6}, {7,6}, {8,6} },
            { {9,6}, {10,6}, {7,7}, {8,7}, {9,7}, {10,7} },
            { {8,8}, {9,8}, {10,8}, {9,9}, {10,9}, {10,10} },
    };

    @PostConstruct
    public void seedAndReconcile() {
        String op = Operation.SUB.value();
        List<String> belts = Belt.COLORED_ORDER;
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;

        for (int level = 1; level <= SUB_FACTS.length; level++) {
            for (int b = 0; b < belts.size(); b++) {
                String belt = belts.get(b);
                String levelStr = String.valueOf(level);
                int[] pair = SUB_FACTS[level - 1][b];
                validateSubtractionFact(pair[0], pair[1]);

                Optional<Catalog> existing = catalogRepo.findByOperationAndLevelAndBelt(op, levelStr, belt);
                if (existing.isEmpty()) {
                    catalogRepo.save(buildCatalog(op, levelStr, belt, pair[0], pair[1]));
                    inserted++;
                    continue;
                }

                Catalog cat = existing.get();
                Fact first = (cat.getFacts() != null && !cat.getFacts().isEmpty()) ? cat.getFacts().get(0) : null;
                if (first != null
                        && Objects.equals(first.getA(), pair[0])
                        && Objects.equals(first.getB(), pair[1])) {
                    unchanged++;
                    continue;
                }

                Fact prevFact = first;
                cat.setFacts(List.of(buildFact(pair[0], pair[1])));
                catalogRepo.save(cat);
                updated++;

                String prevDesc = prevFact == null ? "<empty>" : prevFact.getA() + "-" + prevFact.getB();
                log.info("[INIT] Reconciled subtraction catalog L{} {}: {} -> {}-{}",
                        level, belt, prevDesc, pair[0], pair[1]);
            }
        }

        int expected = SUB_FACTS.length * belts.size();
        if (inserted + updated > 0 || unchanged != expected) {
            log.info("[INIT] Subtraction catalog seed: {} inserted, {} updated, {} unchanged",
                    inserted, updated, unchanged);
        }
    }

    private Catalog buildCatalog(String op, String level, String belt, int a, int b) {
        Catalog cat = new Catalog();
        cat.setOperation(op);
        cat.setLevel(level);
        cat.setBelt(belt);
        List<Fact> facts = new ArrayList<>();
        facts.add(buildFact(a, b));
        cat.setFacts(facts);
        return cat;
    }

    private Fact buildFact(int a, int b) {
        Fact fact = new Fact();
        fact.setA(a);
        fact.setB(b);
        fact.setIdentical(a == b);
        return fact;
    }

    static void validateSubtractionFact(int a, int b) {
        if (a < 0 || b < 0 || a < b) {
            throw new IllegalStateException("Subtraction fact rejected: operands must satisfy a >= b >= 0 (a=" + a + ", b=" + b + ")");
        }
    }
}
