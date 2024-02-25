package ru.vzotov.ai.application;

import com.google.common.collect.Lists;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.Builder;
import ru.vzotov.purchase.domain.model.Purchase;

import java.util.List;
import java.util.Objects;

import static java.util.Collections.singletonList;

public class PurchaseCategoryProcessor {

    private final EmbeddingStoreIngestor ingestor;
    private final int partitionSize;

    @Builder
    public PurchaseCategoryProcessor(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            int partitionSize
    ) {
        if(partitionSize <= 0)
            throw new IllegalArgumentException("partitionSize must be > 0");
        this.partitionSize = partitionSize;
        Objects.requireNonNull(embeddingModel);
        Objects.requireNonNull(embeddingStore);
        this.ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(doc -> singletonList(doc.toTextSegment()))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    public void process(List<Purchase> purchases) {
        Lists.partition(purchases, partitionSize)
                .stream()
                .map(this::transform)
                .forEach(ingestor::ingest);
    }

    List<Document> transform(List<Purchase> purchases) {
        return purchases.stream()
                .map(ItemAction::new)
                .map(action -> new Document(action.text(), action.metadata()))
                .toList();
    }

    static class ItemAction {
        private static final String ENTITY_PURCHASE = "purchase";
        private static final String F_ID = "entityId";
        private static final String F_ENTITY = "entity";
        private static final String F_REFERENCE_ID = "reference_id";
        private static final String F_LAST_MODIFIED = "last_modified";

        private final Purchase purchase;
        private final String text;

        public ItemAction(Purchase purchase) {
            this.purchase = purchase;
            this.text = "Purchase '%s' has category '%s' with id '%s'."
                    .formatted(purchase.name(), purchase.category().name(), purchase.category().categoryId().value());
        }

        public Purchase purchase() {
            return purchase;
        }

        public String text() {
            return text;
        }

        public Metadata metadata() {
            return Metadata.from(F_ID, purchase().purchaseId().value())
                    .add(F_ENTITY, ENTITY_PURCHASE)
                    .add(F_LAST_MODIFIED, String.valueOf(purchase().updatedOn().toEpochMilli()))
                    .add(F_REFERENCE_ID, purchase().category().categoryId().value());
        }
    }

}
