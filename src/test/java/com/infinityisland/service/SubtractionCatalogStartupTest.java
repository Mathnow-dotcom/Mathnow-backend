package com.infinityisland.service;

import com.infinityisland.dao.Catalog;
import com.infinityisland.model.Belt;
import com.infinityisland.repositories.CatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SubtractionCatalogStartupTest {
    @Test void reconcilesAtStartupWithGlobalLazyInitializationAndWithoutQuizHelper() throws Exception {
        var field = SubtractionCatalogSeeder.class.getDeclaredField("SUB_FACTS");
        field.setAccessible(true);
        int[][][] levels = (int[][][]) field.get(null);
        Map<String, Catalog> documents = new HashMap<>();
        for (int level = 0; level < levels.length; level++) {
            for (int belt = 0; belt < Belt.COLORED_ORDER.size(); belt++) {
                var catalog = new Catalog();
                catalog.setOperation("sub"); catalog.setLevel(String.valueOf(level + 1));
                catalog.setBelt(Belt.COLORED_ORDER.get(belt));
                var fact = new Catalog.Fact();
                fact.setA(levels[level][belt][0]); fact.setB(levels[level][belt][1]);
                catalog.setFacts(List.of(fact));
                documents.put(catalog.getLevel() + ":" + catalog.getBelt(), catalog);
            }
        }
        var stale = documents.get("3:white");
        stale.getFacts().get(0).setA(2); stale.getFacts().get(0).setB(1);
        List<Catalog> saves = new ArrayList<>();
        CatalogRepository repository = (CatalogRepository) Proxy.newProxyInstance(
            CatalogRepository.class.getClassLoader(), new Class<?>[]{CatalogRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "findByOperationAndLevelAndBelt" -> Optional.ofNullable(documents.get(args[1] + ":" + args[2]));
                case "save" -> { saves.add((Catalog) args[0]); yield args[0]; }
                case "toString" -> "TestCatalogRepository";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
            context.registerBean(CatalogRepository.class, () -> repository);
            context.register(SubtractionCatalogSeeder.class);
            context.refresh(); // No explicit seeder lookup and no QuizHelper initialization.
            assertEquals(1, saves.size());
            assertSame(stale, saves.get(0));
            assertEquals(3, stale.getFacts().get(0).getA());
            assertEquals(2, stale.getFacts().get(0).getB());
            assertEquals(66, AssessmentService.buildPool(new ArrayList<>(documents.values()), "B").size());
            context.getBean(SubtractionCatalogSeeder.class).seedAndReconcile();
            assertEquals(1, saves.size(), "An unchanged catalog must not be rewritten");
        }
    }
}
