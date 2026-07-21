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

/**
 * Seeds and reconciles the multiplication catalog on startup.
 *
 * Mirrors the canonical layout from seed-multiplication-catalogs.js
 * (10 levels × 6 colored belts = 60 entries). Each (level, belt) holds a
 * single canonical (a, b) pair used as the introduced fact for that slot.
 *
 * Levels 6-10 contain "repeated" pairs from earlier levels (single-digit
 * cap forces fact reuse). The repeated flag is informational; the canonical
 * pair semantics are identical.
 *
 * Reconciliation policy: upsert by (operation, level, belt). If a document
 * exists with a different (a, b) pair, it is replaced and the change logged.
 * If absent, the document is inserted. Any extra mul catalog rows not in this
 * seed are left untouched (no destructive cleanup).
 */
@Component("multiplicationCatalogSeeder")
@Order(0)
public class MultiplicationCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(MultiplicationCatalogSeeder.class);

    @Autowired
    private CatalogRepository catalogRepo;

    // Canonical layout from the Scope & Sequence (v1.7).
    // Each (a, b) is the "new fact" pair for that (level, belt) slot. The quiz builders
    // generate the commutative variant (b, a) automatically when a != b.
    //
    // L1-L2: ×0 facts (the identity-by-zero teaching block).
    // L2 brown introduces 1×1 (transition to the ×1 family).
    // L3: ×1 introductions (1×N).
    // L4: finishes ×1 and starts ×2 with 2×2 identity at blue.
    // L5: ×2 continues, ends with 3×3 identity at brown.
    // L6-L8: progress through ×3, ×4, ×5 with identity at white and 6×6 identity at L8 brown.
    // L9-L10: ×6/×7/×8/×9 wrap-up with L10 repeating L9's identities for review.
    //
    // Two S&S typos preserved verbatim (per product decision 2026-06-11):
    //   * L3 yellow duplicates L3 white = (1, 2). Will revisit if curriculum owner clarifies.
    //   * L9 yellow uses (6, 8); the S&S printed "9 x 6" as the second variant but the
    //     correct commutative for 6×8 is 8×6, which the builder generates automatically.
    private static final int[][][] MUL_FACTS = {
            // L1: ×0 introduces 0..5 (paired with 0)
            { {0,0}, {0,1}, {0,2}, {0,3}, {0,4}, {0,5} },
            // L2: ×0 continues 5..9 (white repeats L1 brown); brown transitions to 1×1
            { {0,5}, {0,6}, {0,7}, {0,8}, {0,9}, {1,1} },
            // L3: ×1 (1×2..1×6); L3 yellow == L3 white per S&S
            { {1,2}, {1,2}, {1,3}, {1,4}, {1,5}, {1,6} },
            // L4: ×1 finishes (1×7..1×9) then ×2 begins (2×2 identity at blue)
            { {1,7}, {1,8}, {1,9}, {2,2}, {2,3}, {2,4} },
            // L5: ×2 continues, brown closes with 3×3
            { {2,5}, {2,6}, {2,7}, {2,8}, {2,9}, {3,3} },
            // L6: ×3 (no identity; all paired)
            { {3,4}, {3,5}, {3,6}, {3,7}, {3,8}, {3,9} },
            // L7: ×4 starts with 4×4 identity, then 4×5..4×9
            { {4,4}, {4,5}, {4,6}, {4,7}, {4,8}, {4,9} },
            // L8: ×5 starts with 5×5 identity, brown closes with 6×6
            { {5,5}, {5,6}, {5,7}, {5,8}, {5,9}, {6,6} },
            // L9: ×6/×7 transition (6×7 white through 7×9 brown)
            { {6,7}, {6,8}, {6,9}, {7,7}, {7,8}, {7,9} },
            // L10: ×8/×9 review — each fact appears in two belts
            { {8,8}, {8,9}, {9,9}, {8,8}, {8,9}, {9,9} },
    };

    @PostConstruct
    public void seedAndReconcile() {
        String op = Operation.MUL.value();
        List<String> belts = Belt.COLORED_ORDER;
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;

        for (int level = 1; level <= MUL_FACTS.length; level++) {
            for (int b = 0; b < belts.size(); b++) {
                String belt = belts.get(b);
                String levelStr = String.valueOf(level);
                int[] pair = MUL_FACTS[level - 1][b];
                boolean identical = pair[0] == pair[1];

                Optional<Catalog> existing = catalogRepo.findByOperationAndLevelAndBelt(op, levelStr, belt);

                if (existing.isEmpty()) {
                    catalogRepo.save(buildCatalog(op, levelStr, belt, pair[0], pair[1], identical));
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
                Fact fact = new Fact();
                fact.setA(pair[0]);
                fact.setB(pair[1]);
                fact.setIdentical(identical);
                List<Fact> facts = new ArrayList<>();
                facts.add(fact);
                cat.setFacts(facts);
                catalogRepo.save(cat);
                updated++;

                String prevDesc = prevFact == null
                        ? "<empty>"
                        : prevFact.getA() + "x" + prevFact.getB();
                log.info("[INIT] Reconciled mul catalog L{} {}: {} → {}x{}",
                        level, belt, prevDesc, pair[0], pair[1]);
            }
        }

        if (inserted + updated > 0 || unchanged != 60) {
            log.info("[INIT] Multiplication catalog seed: {} inserted, {} updated, {} unchanged",
                    inserted, updated, unchanged);
        }
    }

    private Catalog buildCatalog(String op, String level, String belt, int a, int b, boolean identical) {
        Fact fact = new Fact();
        fact.setA(a);
        fact.setB(b);
        fact.setIdentical(identical);

        Catalog cat = new Catalog();
        cat.setOperation(op);
        cat.setLevel(level);
        cat.setBelt(belt);
        List<Fact> facts = new ArrayList<>();
        facts.add(fact);
        cat.setFacts(facts);
        return cat;
    }
}
