package ru.vzotov.ai.gigachat;

import gigachat.v1.ChatServiceGrpc;
import gigachat.v1.Gigachatv1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vzotov.ai.domain.ChatModel;

import java.util.stream.Stream;

public class GigachatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GigachatModel.class);

    private final ChatServiceGrpc.ChatServiceBlockingStub gigachat;
    private final String modelName;

    public GigachatModel(ChatServiceGrpc.ChatServiceBlockingStub gigachat, String modelName) {
        this.gigachat = gigachat;
        this.modelName = modelName;
    }

    @Override
    public Stream<String> chat(String prompt) {
        log.debug("Prompt: {}", prompt);

        Gigachatv1.ChatResponse chat = gigachat.chat(Gigachatv1.ChatRequest.newBuilder()
//                .setModel("GigaChat:latest")
                .setModel(modelName)
                .setOptions(Gigachatv1.ChatOptions.newBuilder()
                        .setTemperature(0.7f)
                        .setMaxTokens(4096)
                        .build())
                .addMessages(Gigachatv1.Message.newBuilder()
                        .setRole("user")
                        .setContent(prompt).build())
                .build());
        return chat.getAlternativesList().stream()
                .map(alt -> alt.getMessage().getContent())
                .peek(answer -> log.debug("Answer: {}", answer));

    }
}
