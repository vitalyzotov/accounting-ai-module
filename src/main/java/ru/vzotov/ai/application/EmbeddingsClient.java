package ru.vzotov.ai.application;

public interface EmbeddingsClient {
    Float[] embed(String text);
}
