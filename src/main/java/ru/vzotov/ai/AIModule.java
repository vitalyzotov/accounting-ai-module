package ru.vzotov.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.vzotov.accounting.domain.model.PersistentPropertyRepository;
import ru.vzotov.ai.application.PurchaseCategoryIndexer;
import ru.vzotov.ai.application.PurchaseCategoryProcessor;
import ru.vzotov.ai.interfaces.facade.AIFacade;
import ru.vzotov.ai.interfaces.facade.impl.AIFacadeImpl;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryRepository;
import ru.vzotov.langchain4j.gigachat.spring.AutoConfig;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

@ConditionalOnProperty(prefix = AIModuleProperties.PREFIX, name = "enabled")
@Configuration
@ImportAutoConfiguration(AutoConfig.class)
@EnableConfigurationProperties(AIModuleProperties.class)
public class AIModule {

    private static final Logger log = LoggerFactory.getLogger(AIModule.class);

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(AIModuleProperties properties) {
        PgVectorConfigProperties config = properties.getPgvector();
        return PgVectorEmbeddingStore.builder()
                .host(config.getHost())
                .port(config.getPort())
                .database(config.getDatabase())
                .user(config.getUser())
                .password(config.getPassword())
                .dimension(config.getDimension())
                .table(config.getTable())
                .createTable(config.getCreate())
                .dropTableFirst(config.getDrop())
                .useIndex(true)
                .indexListSize(config.getIndexListSize())
                .build();
    }

    @Bean
    PurchaseCategoryProcessor processor(EmbeddingStore<TextSegment> embeddingStore,
                                        EmbeddingModel embeddingModel,
                                        AIModuleProperties properties) {
        PurchasesConfigProperties config = properties.getPurchases();
        return PurchaseCategoryProcessor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .partitionSize(config.getPartitionSize())
                .build();
    }

    @Bean
    AIFacade facade(
            PurchaseCategoryRepository purchaseCategoryRepository,
            PurchaseRepository purchaseRepository,
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            ChatLanguageModel chatLanguageModel,
            ObjectMapper objectMapper) {
        return AIFacadeImpl.builder()
                .purchaseCategoryRepository(purchaseCategoryRepository)
                .purchaseRepository(purchaseRepository)
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .chatLanguageModel(chatLanguageModel)
                .objectMapper(objectMapper)
                .build();
    }

    @Bean
    @ConditionalOnBean(PersistentPropertyRepository.class)
    PurchaseCategoryIndexer indexer(ObjectMapper objectMapper,
                                    PurchaseRepository purchaseRepository,
                                    PersistentPropertyRepository propertyRepository,
                                    PurchaseCategoryProcessor processor) {
        return PurchaseCategoryIndexer.builder()
                .objectMapper(objectMapper)
                .purchaseRepository(purchaseRepository)
                .propertyRepository(propertyRepository)
                .processor(processor)
                .build();
    }

}
