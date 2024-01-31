package ru.vzotov.ai.domain;

public interface EmbeddingsModel {
    Float[] embed(String text);
}
