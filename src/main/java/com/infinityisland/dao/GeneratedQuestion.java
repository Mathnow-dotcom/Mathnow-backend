package com.infinityisland.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document("generated_questions")
public class GeneratedQuestion {
    @Id
    private String id;

    private String templateRef;
    private String operation;
    // Node uses numbers; keep level as integer
    private Integer level;
    private String beltOrDegree;

    private Params params;
    private String question;
    private Integer correctAnswer;
    private List<Integer> choices = new ArrayList<>();
    private List<String> textChoices = new ArrayList<>();  // Rocket mode: expression text choices

    private String source; // "current" | "previous"
    private String seed;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
    private int __v;

    public static class Params {
        private Integer a;
        private Integer b;

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
    }

    // ----- getters/setters -----
    @JsonIgnore
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTemplateRef() {
        return templateRef;
    }

    public void setTemplateRef(String templateRef) {
        this.templateRef = templateRef;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getBeltOrDegree() {
        return beltOrDegree;
    }

    public void setBeltOrDegree(String beltOrDegree) {
        this.beltOrDegree = beltOrDegree;
    }

    public Params getParams() {
        return params;
    }

    public void setParams(Params params) {
        this.params = params;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(Integer correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public List<Integer> getChoices() {
        return choices;
    }

    public void setChoices(List<Integer> choices) {
        this.choices = choices;
    }

    public List<String> getTextChoices() {
        return textChoices;
    }

    public void setTextChoices(List<String> textChoices) {
        this.textChoices = textChoices;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSeed() {
        return seed;
    }

    public void setSeed(String seed) {
        this.seed = seed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Node-style id alias
    @JsonProperty("_id")
    public String get_id() {
        return id;
    }

    // Ensure no stray top-level a/b leak
    @JsonIgnore
    public Integer getA() {
        return null;
    }

    @JsonIgnore
    public Integer getB() {
        return null;
    }

    public int get__v() {
        return __v;
    }

    public void set__v(int __v) {
        this.__v = __v;
    }
}