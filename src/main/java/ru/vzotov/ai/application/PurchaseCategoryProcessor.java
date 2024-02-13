package ru.vzotov.ai.application;

import com.google.common.collect.Lists;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.Builder;
import ru.vzotov.ai.domain.Templates;
import ru.vzotov.purchase.domain.model.Purchase;

import java.util.List;
import java.util.Objects;

import static java.util.Collections.singletonList;
import static ru.vzotov.ai.domain.PurchaseCategoriesCollection.ENTITY_PURCHASE;
import static ru.vzotov.ai.domain.PurchaseCategoriesCollection.F_ENTITY;
import static ru.vzotov.ai.domain.PurchaseCategoriesCollection.F_ID;
import static ru.vzotov.ai.domain.PurchaseCategoriesCollection.F_LAST_MODIFIED;

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
        private final Purchase purchase;
        private final String text;

        public ItemAction(Purchase purchase) {
            this.purchase = purchase;
            this.text = Templates.purchaseHasCategory(purchase);
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
                    .add(F_LAST_MODIFIED, String.valueOf(purchase().updatedOn().toEpochMilli()));
        }
    }

}
