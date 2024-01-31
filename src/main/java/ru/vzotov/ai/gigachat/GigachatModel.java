package ru.vzotov.ai.gigachat;

import gigachat.v1.ChatServiceGrpc;
import gigachat.v1.Gigachatv1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vzotov.ai.domain.ChatModel;
import ru.vzotov.ai.interfaces.facade.impl.AIFacadeImpl;

import java.util.stream.Stream;

public class GigachatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GigachatModel.class);

    private final ChatServiceGrpc.ChatServiceBlockingStub gigachat;

    public GigachatModel(ChatServiceGrpc.ChatServiceBlockingStub gigachat) {
        this.gigachat = gigachat;
    }

    @Override
    public Stream<String> chat(String prompt) {
        log.debug("Prompt: {}", prompt);

        Gigachatv1.ChatResponse chat = gigachat.chat(Gigachatv1.ChatRequest.newBuilder()
                .setModel("GigaChat:latest")
                .addMessages(Gigachatv1.Message.newBuilder()
                        .setRole("user")
                        .setContent(prompt).build())
                .build());
        return chat.getAlternativesList().stream()
                .map(alt -> alt.getMessage().getContent())
                .peek(answer -> log.debug("Answer: {}", answer));

    }
}
