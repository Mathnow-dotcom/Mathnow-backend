package com.infinityisland.dao;

import com.infinityisland.service.ProgramProgressionRules.State;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/** Separate from legacy progress and standalone assessment history. */
@Document("program_progress")
public class ProgramProgress {
    @Id public String id;
    @Version public Long version;
    public int schemaVersion = 1;
    public String enrollment = UUID.randomUUID().toString();
    public String activeSubject;
    public Date migratedAt;
    public Set<String> grandfatheredAccess = new HashSet<>();
    public Map<String, State> subjects = new LinkedHashMap<>();
}
