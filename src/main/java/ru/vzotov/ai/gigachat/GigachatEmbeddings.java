package ru.vzotov.ai.gigachat;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.client.RestOperations;
import ru.vzotov.ai.domain.EmbeddingsModel;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class GigachatEmbeddings implements EmbeddingsModel {

    private final RestOperations restOperations;

    public GigachatEmbeddings(RestOperations restOperations) {
        this.restOperations = restOperations;
    }

    @Override
    public Float[] embed(String text) {
        EmbeddingsResponse response = restOperations.postForObject(
                "https://gigachat.devices.sberbank.ru/api/v1/embeddings",
                new EmbeddingsRequest(Collections.singletonList(text)),
                EmbeddingsResponse.class
        );

        return Objects.requireNonNull(response).data().get(0).embedding();
    }

    record EmbeddingsRequest(
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String model,
            List<String> input
    ) {
        public EmbeddingsRequest(List<String> input) {
            this("Embeddings", input);
        }
    }

    record EmbeddingsResponse(
            String object,
            List<Data> data,
            String model
    ) {
        record Data(String object, Float[] embedding, int index) {
        }
    }
}
