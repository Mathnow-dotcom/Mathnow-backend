package com.infinityisland.service;

import com.infinityisland.dao.Catalog;
import com.infinityisland.dao.Catalog.Fact;
import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;
import com.infinityisland.repositories.CatalogRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Seeds the six-level Fraction scope-and-sequence supplied by curriculum. */
@Component("fractionCatalogSeeder")
@Order(0)
public class FractionCatalogSeeder {
    private static final Logger log = LoggerFactory.getLogger(FractionCatalogSeeder.class);

    // Each pair is numerator, denominator; decimal answers are rendered to three places.
    private static final int[][][] FRACTION_FACTS = {
            { {1,2}, {1,3}, {1,4}, {1,5}, {1,6}, {1,7} },
            { {1,8}, {1,9}, {2,3}, {2,4}, {2,5}, {2,6} },
            { {2,7}, {2,8}, {2,9}, {3,4}, {3,5}, {3,6} },
            { {3,7}, {3,8}, {3,9}, {4,5}, {4,6}, {4,7} },
            { {4,8}, {4,9}, {5,6}, {5,7}, {5,8}, {5,9} },
            { {6,7}, {6,8}, {6,9}, {7,8}, {7,9}, {8,9} },
    };

    private final CatalogRepository catalogRepo;

    public FractionCatalogSeeder(CatalogRepository catalogRepo) {
        this.catalogRepo = catalogRepo;
    }

    @PostConstruct
    public void seedAndReconcile() {
        String operation = Operation.FRAC.value();
        List<String> belts = Belt.COLORED_ORDER;
        int inserted = 0;
        int updated = 0;

        for (int level = 1; level <= FRACTION_FACTS.length; level++) {
            for (int beltIndex = 0; beltIndex < belts.size(); beltIndex++) {
                String belt = belts.get(beltIndex);
                int[] pair = FRACTION_FACTS[level - 1][beltIndex];
                Optional<Catalog> existing = catalogRepo.findByOperationAndLevelAndBelt(
                        operation, String.valueOf(level), belt);
                if (existing.isEmpty()) {
                    catalogRepo.save(catalog(operation, level, belt, pair));
                    inserted++;
                    continue;
                }
                Catalog.Fact current = existing.get().getFacts() == null || existing.get().getFacts().isEmpty()
                        ? null : existing.get().getFacts().get(0);
                if (current == null || !Objects.equals(current.getA(), pair[0]) || !Objects.equals(current.getB(), pair[1])) {
                    existing.get().setFacts(List.of(fact(pair)));
                    catalogRepo.save(existing.get());
                    updated++;
                }
            }
        }
        if (inserted + updated > 0) {
            log.info("[INIT] Fraction catalog seed: {} inserted, {} reconciled", inserted, updated);
        }
    }

    private Catalog catalog(String operation, int level, String belt, int[] pair) {
        Catalog catalog = new Catalog();
        catalog.setOperation(operation);
        catalog.setLevel(String.valueOf(level));
        catalog.setBelt(belt);
        catalog.setFacts(List.of(fact(pair)));
        return catalog;
    }

    private Fact fact(int[] pair) {
        if (pair[0] <= 0 || pair[1] <= 0 || pair[0] >= pair[1]) {
            throw new IllegalStateException("Fraction fact must be a proper positive fraction");
        }
        Fact fact = new Fact();
        fact.setA(pair[0]);
        fact.setB(pair[1]);
        fact.setIdentical(false);
        return fact;
    }
}
