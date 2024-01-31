package ru.vzotov.ai.domain;

import java.util.stream.Stream;

public interface ChatModel {
    Stream<String> chat(String prompt);
}
