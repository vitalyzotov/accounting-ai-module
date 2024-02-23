package ru.vzotov.ai.interfaces.facade.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.annotation.Secured;
import org.springframework.transaction.annotation.Transactional;
import ru.vzotov.accounting.infrastructure.security.SecurityUtils;
import ru.vzotov.accounting.interfaces.purchases.PurchasesApi;
import ru.vzotov.accounting.interfaces.purchases.facade.impl.assembler.PurchaseAssembler;
import ru.vzotov.ai.interfaces.facade.AIFacade;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategory;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryId;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryRepository;
import ru.vzotov.purchase.domain.model.Purchase;
import ru.vzotov.purchase.domain.model.PurchaseId;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class AIFacadeImpl implements AIFacade {

    private static final Logger log = LoggerFactory.getLogger(AIFacadeImpl.class);

    private final PurchaseCategoryRepository purchaseCategoryRepository;
    private final PurchaseRepository purchaseRepository;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    @Builder
    public AIFacadeImpl(PurchaseCategoryRepository purchaseCategoryRepository,
                        PurchaseRepository purchaseRepository,
                        EmbeddingStore<TextSegment> embeddingStore,
                        EmbeddingModel embeddingModel,
                        ChatLanguageModel chatLanguageModel,
                        ObjectMapper objectMapper
    ) {
        this.purchaseCategoryRepository = purchaseCategoryRepository;
        this.purchaseRepository = purchaseRepository;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(value = "accounting-tx", readOnly = true)
    @Secured({"ROLE_USER"})
    public List<PurchasesApi.Purchase> classifyPurchasesBySimilarity(List<String> purchaseIdList) {
        final int samples = 5;
        final int threshold = samples - 1;
        log.debug("Start hybrid classification of purchases {}. Samples={}, threshold={}",
                purchaseIdList, samples, threshold);
        try {
            final List<PurchaseCategory> categories = purchaseCategoryRepository.findAll(SecurityUtils.getCurrentPerson());
            final Map<PurchaseCategoryId, PurchaseCategory> purchaseCategoryMap = categories.stream()
                    .collect(Collectors.toMap(PurchaseCategory::categoryId, it -> it));
            final List<Purchase> purchases = loadPurchases(purchaseIdList);
            final List<Purchase> classified = new ArrayList<>();
            final List<String> classifyByChatModel = new ArrayList<>();

            log.debug("Get embeddings");
            final List<Embedding> embeddings = embeddingModel.embedAll(
                    purchases.stream().map(p -> TextSegment.from(p.name())).toList()).content();

            IntStream.range(0, purchases.size())
                    .boxed()
                    .parallel()
                    .forEach(i -> {
                        Purchase purchase = purchases.get(i);
                        Embedding embedding = embeddings.get(i);

                        log.debug("{}:: Find relevant documents for purchase {}, {}", i, purchase.purchaseId(), purchase.name());

                        List<EmbeddingMatch<TextSegment>> relevant = embeddingStore.findRelevant(embedding, samples, 0.8);
                        Map<PurchaseCategoryId, Long> relevantCategories = relevant.stream()
                                .map(match -> match.embedded().text())
                                .peek(text -> log.debug("Sample: {}", text))
                                .map(AIFacadeImpl::extractCategoryId)
                                .filter(Objects::nonNull)
                                .map(PurchaseCategoryId::new)
                                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));

                        Optional<PurchaseCategory> optionalKey = relevantCategories.entrySet().stream()
                                .filter(entry -> entry.getValue() > threshold)
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .map(purchaseCategoryMap::get);

                        optionalKey.ifPresentOrElse(category -> {
                                    log.debug("{}:: Relevant category: {}, {}", i, category.categoryId(), category.name());
                                    purchase.assignCategory(category);
                                    classified.add(purchase);
                                },
                                () -> classifyByChatModel.add(purchase.purchaseId().value()));

                    });

            final List<PurchasesApi.Purchase> fromChatModel = new ArrayList<>();
            if (!classifyByChatModel.isEmpty()) {
                log.debug("This list of purchases is ambiguous. We will use chat model for classification: {}", classifyByChatModel);
                Lists.partition(classifyByChatModel, 5).stream()
                        .map(this::classifyPurchases)
                        .forEach(fromChatModel::addAll);
            }


            return Stream.concat(
                    new PurchaseAssembler().toDTOList(classified).stream(),
                    fromChatModel.stream()
            ).toList();
        } finally {
            log.debug("Done");
        }
    }

    private static String extractCategoryId(String document) {
        Pattern pattern = Pattern.compile("with id '(.+?)'");
        Matcher matcher = pattern.matcher(document);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    @Transactional(value = "accounting-tx", readOnly = true)
    @Secured({"ROLE_USER"})
    public List<PurchasesApi.Purchase> classifyPurchases(List<String> purchaseIdList) {
        final List<PurchaseCategory> categories = purchaseCategoryRepository.findAll(SecurityUtils.getCurrentPerson());
        final Map<PurchaseCategoryId, PurchaseCategory> purchaseCategoryMap = categories.stream()
                .collect(Collectors.toMap(PurchaseCategory::categoryId, it -> it));

        // todo: we can embed multiple queries in one request (use embedAll)
        // The content retriever is responsible for retrieving relevant content based on a text query.
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2) // on each interaction we will retrieve the 2 most relevant segments
                .minScore(0.5) // we want to retrieve segments at least somewhat similar to user query
                .build();

        // Aggregates all Contents retrieved from all ContentRetrievers using all queries.
        ContentAggregator contentAggregator = new DefaultContentAggregator();

        // todo: we can use special kind of query (list of simple queries)
        // Splits collection query to multiple queries: one query for each item
        QueryTransformer queryTransformer = query -> {
            UserMessage userMessage = query.metadata().userMessage();

            return jsonMessage(userMessage, objectMapper.constructType(AgentRequest.class),
                    (AgentRequest data) -> data.purchases().stream()
                            .map(s -> Query.from(s.purchaseName(), query.metadata()))
                            .toList());
        };

        ContentInjector defaultContentInjector = DefaultContentInjector.builder().build();
        ContentInjector contentInjector = (contents, userMessage) -> defaultContentInjector.inject(contents,
                UserMessage.from(jsonMessage(userMessage, objectMapper.constructType(AgentRequest.class),
                        (AgentRequest data) -> {
                            try {
                                return """
                                        Please answer which categories the list of purchases belong to:
                                        ```json
                                        %s
                                        ```
                                                                                
                                        The purchase category must be one of this list of possible categories:
                                        ```json
                                        %s
                                        ```
                                        """.formatted(
                                        objectMapper.writeValueAsString(data.purchases()),
                                        objectMapper.writeValueAsString(categories.stream().map(c -> new CategoryData(c.name(), c.categoryId().value())).toList())
                                );
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })));

        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .queryTransformer(queryTransformer)
                .contentAggregator(contentAggregator)
                .contentInjector(contentInjector)
                .build();

        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        PurchaseClassifyingAgent agent = AiServices.builder(PurchaseClassifyingAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .retrievalAugmentor(retrievalAugmentor)
                .chatMemory(chatMemory)
                .build();

        final List<Purchase> purchases = loadPurchases(purchaseIdList);
        final Map<PurchaseId, Purchase> purchaseMap = purchases.stream()
                .collect(Collectors.toMap(Purchase::purchaseId, it -> it));
        try {
            AgentResponse response = agent.classify(
                    objectMapper.writeValueAsString(new AgentRequest(purchases.stream().map(p -> new IdNameOfPurchase(p.purchaseId().value(), p.name())).toList())));
            Optional.ofNullable(response)
                    .map(AgentResponse::classification)
                    .stream().flatMap(List::stream)
                    .forEach(item -> {
                        final Purchase p = Optional.ofNullable(item.getPurchaseId())
                                .map(PurchaseId::new)
                                .map(purchaseMap::get)
                                .orElse(null);
                        if (p == null) return;
                        final PurchaseCategory targetCategory = Optional.ofNullable(item.getCategoryId())
                                .map(PurchaseCategoryId::new)
                                .map(purchaseCategoryMap::get)
                                .orElse(null);
                        if (targetCategory == null) return;
                        p.assignCategory(targetCategory);
                    });

            return new PurchaseAssembler().toDTOList(purchases);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private List<Purchase> loadPurchases(List<String> purchaseIdList) {
        return purchaseIdList.stream()
                .filter(Objects::nonNull)
                .map(PurchaseId::new)
                .map(purchaseRepository::find)
                .filter(Objects::nonNull)
                .toList();
    }

    record CategoryData(String name, String id) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    static class PurchaseCategoryData {
        private String purchaseId;
        private String purchaseName;
        private String categoryId;
        private String categoryName;
    }

    record IdNameOfPurchase(String purchaseId, String purchaseName) {
    }

    interface PurchaseClassifyingAgent {
        AgentResponse classify(String agentQuery);
    }

    record AgentResponse(
            @Description("""
                    array of objects {"purchaseId": (type: string), "purchaseName": (type: string), "categoryId": (type: string), "categoryName": (type: string)}
                    """)
            List<PurchaseCategoryData> classification) {
    }

    record AgentRequest(List<IdNameOfPurchase> purchases) {
    }

    <T, R> R jsonMessage(UserMessage userMessage, JavaType type, Function<T, R> action) {
        if (!userMessage.hasSingleText())
            throw new IllegalArgumentException("We support only single-text messages");
        Content content = userMessage.contents().get(0);
        if (content instanceof TextContent text) {
            try {
                T data = objectMapper.readValue(text.text(), type);
                return action.apply(data);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new IllegalArgumentException("Unsupported content type");
        }
    }
}
