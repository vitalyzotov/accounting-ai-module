package ru.vzotov.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gigachat.v1.ChatServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.vzotov.accounting.domain.model.PersistentProperty;
import ru.vzotov.accounting.domain.model.PersistentPropertyId;
import ru.vzotov.accounting.domain.model.PersistentPropertyRepository;
import ru.vzotov.ai.domain.EmbeddingsModel;
import ru.vzotov.ai.domain.PurchaseCategoriesCollection;
import ru.vzotov.ai.domain.Templates;
import ru.vzotov.ai.util.BearerToken;
import ru.vzotov.purchase.domain.model.Purchase;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singletonList;
import static ru.vzotov.purchases.domain.model.PurchaseSpecifications.updatedAfter;

/**
 * Indexes purchase categories for vector search.
 */
@Component
public class PurchaseCategoryIndexer implements PurchaseCategoriesCollection {
    private static final Logger log = LoggerFactory.getLogger(PurchaseCategoryIndexer.class);

    private final String collectionName;
    private final ObjectMapper objectMapper;
    private final PurchaseRepository purchaseRepository;
    private final PersistentPropertyRepository propertyRepository;
    private final MilvusServiceClient milvusClient;
    private final EmbeddingsModel embeddingsModel;
    private final ObjectFactory<OAuth2AccessToken> accessTokenFactory;

    public PurchaseCategoryIndexer(
            @Value("${accounting.ai.purchases.collection}")
            String collectionName,
            ObjectMapper objectMapper,
            PurchaseRepository purchaseRepository,
            PersistentPropertyRepository propertyRepository,
            MilvusServiceClient milvusClient,
            EmbeddingsModel embeddingsModel,
            ObjectFactory<OAuth2AccessToken> accessTokenFactory) {
        this.collectionName = collectionName;
        this.objectMapper = objectMapper;
        this.purchaseRepository = purchaseRepository;
        this.propertyRepository = propertyRepository;
        this.milvusClient = milvusClient;
        this.embeddingsModel = embeddingsModel;
        this.accessTokenFactory = accessTokenFactory;
    }

    /**
     * Date of the most recent known purchase
     */
    public Instant lastIndexedOn() {
        PersistentProperty prop = propertyRepository.findSystemProperty("ai.purchases");
        if (prop == null) {
            return Instant.EPOCH;
        } else {
            try {
                PurchasesAIProperties props = objectMapper.readValue(prop.value(), PurchasesAIProperties.class);
                return props.lastIndexedOn();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void updateLastIndexedOn(Instant value) {
        PersistentProperty prop = propertyRepository.findSystemProperty("ai.purchases");
        PurchasesAIProperties props = new PurchasesAIProperties(value);//todo: keep other properties
        try {
            propertyRepository.store(new PersistentProperty(
                    prop == null ? PersistentPropertyId.nextId() : prop.propertyId(), "ai.purchases",
                    objectMapper.writeValueAsString(props)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private ChatServiceGrpc.ChatServiceBlockingStub gigachat() {
        AtomicReference<OAuth2AccessToken> tokenValue = new AtomicReference<>();
        ManagedChannel channel = ManagedChannelBuilder.forTarget("gigachat.devices.sberbank.ru").build();
        return ChatServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(new BearerToken(() -> {
                    OAuth2AccessToken token =
                            tokenValue.updateAndGet(t ->
                                    t != null && t.getExpiresAt() != null && Instant.now().isBefore(t.getExpiresAt()) ?
                                            t : accessTokenFactory.getObject());
                    return token.getTokenValue();
                }));
    }

    @Scheduled(initialDelayString = "${accounting.ai.purchases.index.initial-delay}",
            fixedDelayString = "${accounting.ai.purchases.index.delay}")
    @Transactional(value = "accounting-tx")
    public void doIndex() {
        log.info("Start indexing purchases");
        if (!Boolean.TRUE.equals(milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        ).getData())) {
            log.warn("Milvus collection does not exist and will be created");
            createCollection();
        }
        milvusClient.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
        log.debug("Collection {} loaded", collectionName);

        indexPurchases();
    }

    private void createCollection() {
        FieldType pk = FieldType.newBuilder()
                .withName(F_PK)
                .withDataType(DataType.VarChar)
                .withPrimaryKey(true)
                .withAutoID(false)
                .withMaxLength(64)
                .build();
        FieldType lastModified = FieldType.newBuilder()
                .withName(F_LAST_MODIFIED)
                .withDataType(DataType.Int64)
                .build();
        FieldType text = FieldType.newBuilder()
                .withName(F_TEXT)
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();
        FieldType vector = FieldType.newBuilder()
                .withName(F_VECTOR)
                .withDataType(DataType.FloatVector)
                .withDimension(1024)
                .build();
        milvusClient.createCollection(
                CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withDescription("Purchase category search")
                        .withShardsNum(2)
                        .addFieldType(pk)
                        .addFieldType(lastModified)
                        .addFieldType(text)
                        .addFieldType(vector)
                        .withEnableDynamicField(true)
                        .build()
        );

        milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName(vector.getName())
                        .withIndexType(IndexType.HNSW)
                        .withMetricType(MetricType.L2)
                        .withExtraParam("{\"M\": 8, \"efConstruction\": 64}")
                        .withSyncMode(Boolean.FALSE)
                        .build()
        );
        milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName(lastModified.getName())
                        .withIndexName(F_LAST_MODIFIED)
                        .withIndexType(IndexType.STL_SORT)
                        .build()
        );
    }

    private void indexPurchases() {
        List<Purchase> purchases = purchaseRepository.findAll(updatedAfter(lastIndexedOn()));
        log.debug("There are {} new purchases since last indexing", purchases.size());

        Optional<Instant> max = purchases.stream()
                .filter(purchase -> purchase != null && purchase.category() != null)
                .map(ItemAction::new)
                .peek(action -> Optional.of(embeddingsModel.embed(action.text()))
                        .map(Arrays::asList)
                        .ifPresent(action::setEmbedding))
                .peek(action -> milvusClient.upsert(
                        UpsertParam.newBuilder()
                                .withCollectionName(collectionName)
                                .withFields(action.fields())
                                .build()
                ))
                .map(action -> action.purchase().updatedOn())
                .max(Comparator.naturalOrder());
        milvusClient.flush(FlushParam.newBuilder().addCollectionName(collectionName).build());
        max.ifPresent(this::updateLastIndexedOn);
    }

    static class ItemAction {
        private final Purchase purchase;
        private final String text;
        private List<Float> embedding;

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

        public List<Float> embedding() {
            return embedding;
        }

        public void setEmbedding(List<Float> embedding) {
            this.embedding = embedding;
        }

        public List<InsertParam.Field> fields() {
            return List.of(
                    InsertParam.Field.builder().name(F_PK)
                            .values(singletonList(purchase().purchaseId().value()))
                            .build(),
                    InsertParam.Field.builder().name(F_LAST_MODIFIED)
                            .values(singletonList(purchase().updatedOn().toEpochMilli()))
                            .build(),
                    InsertParam.Field.builder().name(F_TEXT)
                            .values(singletonList(text()))
                            .build(),
                    InsertParam.Field.builder().name(F_VECTOR)
                            .values(singletonList(embedding()))
                            .build()
            );
        }
    }
}
