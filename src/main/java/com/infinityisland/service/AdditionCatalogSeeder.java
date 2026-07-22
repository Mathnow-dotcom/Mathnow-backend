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

@Component("additionCatalogSeeder")
@Order(0)
public class AdditionCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(AdditionCatalogSeeder.class);

    @Autowired
    private CatalogRepository catalogRepo;

    // Canonical addition layout: 19 levels x 6 colored belts.
    // For non-identical facts, quiz builders generate the commutative variant automatically.
    private static final int[][][] ADD_FACTS = {
            { {0,0}, {0,1}, {0,2}, {0,3}, {0,4}, {0,5} },
            { {1,1}, {1,2}, {1,3}, {1,4}, {2,2}, {2,3} },
            { {0,6}, {0,7}, {0,8}, {0,9}, {0,10}, {1,5} },
            { {1,6}, {1,7}, {1,8}, {1,9}, {2,4}, {2,5} },
            { {2,6}, {2,7}, {2,8}, {3,3}, {3,4}, {3,5} },
            { {3,6}, {3,7}, {4,4}, {4,5}, {4,6}, {5,5} },
            { {0,11}, {0,12}, {0,13}, {0,14}, {0,15}, {1,10} },
            { {1,11}, {1,12}, {1,13}, {1,14}, {2,9}, {2,10} },
            { {2,11}, {2,12}, {2,13}, {3,8}, {3,9}, {3,10} },
            { {3,11}, {3,12}, {4,7}, {4,8}, {4,9}, {4,10} },
            { {4,11}, {5,6}, {5,7}, {5,8}, {5,9}, {5,10} },
            { {6,6}, {6,7}, {6,8}, {6,9}, {7,7}, {7,8} },
            { {0,16}, {0,17}, {0,18}, {0,19}, {0,20}, {1,15} },
            { {1,16}, {1,17}, {1,18}, {2,14}, {2,15}, {2,16} },
            { {2,17}, {3,13}, {3,14}, {3,15}, {3,16}, {4,12} },
            { {4,13}, {4,14}, {4,15}, {5,11}, {5,12}, {5,13} },
            { {5,14}, {6,10}, {6,11}, {6,12}, {6,13}, {7,9} },
            { {7,10}, {7,11}, {7,12}, {8,8}, {8,9}, {8,10} },
            { {8,11}, {9,9}, {9,10}, {10,10}, {9,9}, {9,9} },
    };

    @PostConstruct
    public void seedAndReconcile() {
        seedAndReconcile(Operation.ADD.value(), ADD_FACTS, "Addition");
    }

    private void seedAndReconcile(String op, int[][][] facts, String label) {
        List<String> belts = Belt.COLORED_ORDER;
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;

        for (int level = 1; level <= facts.length; level++) {
            for (int b = 0; b < belts.size(); b++) {
                String belt = belts.get(b);
                String levelStr = String.valueOf(level);
                int[] pair = facts[level - 1][b];
                validateAdditionFact(pair[0], pair[1]);

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

                String prevDesc = prevFact == null ? "<empty>" : prevFact.getA() + "+" + prevFact.getB();
                log.info("[INIT] Reconciled {} catalog L{} {}: {} -> {}+{}",
                        label, level, belt, prevDesc, pair[0], pair[1]);
            }
        }

        int expected = facts.length * belts.size();
        if (inserted + updated > 0 || unchanged != expected) {
            log.info("[INIT] {} catalog seed: {} inserted, {} updated, {} unchanged",
                    label, inserted, updated, unchanged);
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

    static void validateAdditionFact(int a, int b) {
        if (a < 0 || b < 0) {
            throw new IllegalStateException("Addition fact rejected: operands must be non-negative (a=" + a + ", b=" + b + ")");
        }
    }
}
