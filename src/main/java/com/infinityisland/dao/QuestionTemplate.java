package com.infinityisland.dao;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("question_templates")
public class QuestionTemplate {
    @Id
    private String id;
    private String operation;
    private String level;
    private String beltOrDegree;
    private Integer a;
    private Integer b;
    private String factType; // identical | non-identical
    private Boolean isIdenticalPair;
    private Object metadata;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getBeltOrDegree() {
        return beltOrDegree;
    }

    public void setBeltOrDegree(String beltOrDegree) {
        this.beltOrDegree = beltOrDegree;
    }

    public Integer getA() {
        return a;
    }

    public void setA(Integer a) {
        this.a = a;
    }

    public Integer getB() {
        return b;
    }

    public void setB(Integer b) {
        this.b = b;
    }

    public String getFactType() {
        return factType;
    }

    public void setFactType(String factType) {
        this.factType = factType;
    }

    public Boolean getIsIdenticalPair() {
        return isIdenticalPair;
    }

    public void setIsIdenticalPair(Boolean isIdenticalPair) {
        this.isIdenticalPair = isIdenticalPair;
    }

    public Object getMetadata() {
        return metadata;
    }

    public void setMetadata(Object metadata) {
        this.metadata = metadata;
    }
}