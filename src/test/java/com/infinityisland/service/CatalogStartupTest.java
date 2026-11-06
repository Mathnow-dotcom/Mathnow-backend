package com.infinityisland.service;

import com.infinityisland.dao.Catalog;
import com.infinityisland.repositories.CatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogStartupTest {
    @Test void seedsAllSubjectsBeforeRequestsAndDoesNotDuplicateOnRestart() {
        Map<List<String>, Catalog> documents = new HashMap<>();
        CatalogRepository repository = mock(CatalogRepository.class);
        when(repository.findByOperationAndLevelAndBelt(anyString(), anyString(), anyString()))
            .thenAnswer(call -> Optional.ofNullable(documents.get(List.of(
                call.getArgument(0), call.getArgument(1), call.getArgument(2)))));
        when(repository.save(any(Catalog.class))).thenAnswer(call -> {
            Catalog catalog = call.getArgument(0);
            documents.put(List.of(catalog.getOperation(), catalog.getLevel(), catalog.getBelt()), catalog);
            return catalog;
        });
        for (int startup = 0; startup < 2; startup++) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
                context.registerBean(CatalogRepository.class, () -> repository);
                context.register(AdditionCatalogSeeder.class, SubtractionCatalogSeeder.class,
                    MultiplicationCatalogSeeder.class, DivisionCatalogSeeder.class, FractionCatalogSeeder.class);
                context.refresh(); // No quiz request or explicit seeder lookup.
                assertEquals(354, documents.size());
                Map<String, Long> counts = new HashMap<>();
                documents.values().forEach(c -> counts.merge(c.getOperation(), 1L, Long::sum));
                assertEquals(Map.of("add", 114L, "sub", 66L, "mul", 60L, "div", 78L, "frac", 36L), counts);
            }
        }
        verify(repository, times(354)).save(any(Catalog.class));
    }
}
