package com.infinityisland.service;

import com.infinityisland.dao.ProgramProgress;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.infinityisland.service.ProgramProgressionRules.*;

class ProgramPersistenceTest {
    @Test void oldCurriculumDocumentsAndNewPartTwoSnapshotsBothRead() throws Exception {
        MongoMappingContext context = new MongoMappingContext(); context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.afterPropertiesSet();
        var base = java.util.stream.IntStream.rangeClosed(1,19).boxed().toList();
        Curriculum old = converter.read(Curriculum.class, new Document("baseLevels",base));
        assertFalse(old.splitAddition());
        assertEquals(1, old.baseLevel(20));
        Curriculum split = new Curriculum(base, 21, null, null);
        Document doc = new Document(); converter.write(split,doc);
        Curriculum restored = converter.read(Curriculum.class,doc);
        assertEquals(11,restored.baseLevel(21));
        assertEquals(split,restored);
    }
    @Test void immutableSnapshotsAndResultsRoundTripThroughMongoConverter() throws Exception {
        MongoMappingContext context = new MongoMappingContext();
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.afterPropertiesSet();
        ProgramProgress p = new ProgramProgress(); p.id = "student"; p.version = 3L; p.activeSubject = "add";
        State state = beginSubject("add", new Curriculum(List.of(1,2)));
        state = completeLevel(completeLevel(state, 1), 2);
        state = finishAssessment(state, "attempt123", 1, 1, List.of(new ItemOutcome(false,3000)));
        p.subjects.put("add", state); p.grandfatheredAccess.add("add");
        Document document = new Document(); converter.write(p, document);
        ProgramProgress read = converter.read(ProgramProgress.class, document);
        assertEquals(p.enrollment, read.enrollment);
        assertEquals(p.version, read.version);
        assertEquals(state, read.subjects.get("add"));
        assertEquals(3, read.subjects.get("add").currentLevel());
        assertEquals(1, read.subjects.get("add").curriculum().baseLevel(3));
    }
}
