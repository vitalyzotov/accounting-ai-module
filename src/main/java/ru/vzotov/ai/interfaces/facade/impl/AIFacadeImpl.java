package ru.vzotov.ai.interfaces.facade.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gigachat.v1.ChatServiceGrpc;
import gigachat.v1.Gigachatv1;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.annotation.Secured;
import org.springframework.transaction.annotation.Transactional;
import ru.vzotov.accounting.infrastructure.security.SecurityUtils;
import ru.vzotov.accounting.interfaces.purchases.PurchasesApi;
import ru.vzotov.accounting.interfaces.purchases.facade.impl.assembler.PurchaseAssembler;
import ru.vzotov.ai.domain.ChatModel;
import ru.vzotov.ai.domain.EmbeddingsModel;
import ru.vzotov.ai.domain.PurchaseCategoriesCollection;
import ru.vzotov.ai.domain.Templates;
import ru.vzotov.ai.interfaces.facade.AIFacade;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategory;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryId;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryRepository;
import ru.vzotov.purchase.domain.model.Purchase;
import ru.vzotov.purchase.domain.model.PurchaseId;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ru.vzotov.ai.domain.Templates.*;

public class AIFacadeImpl implements AIFacade, PurchaseCategoriesCollection {
    private static final Logger log = LoggerFactory.getLogger(AIFacadeImpl.class);

    private final PurchaseCategoryRepository purchaseCategoryRepository;
    private final PurchaseRepository purchaseRepository;
    private final String collectionName;
    private final MilvusServiceClient milvusClient;
    private final EmbeddingsModel embeddingsModel;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public AIFacadeImpl(PurchaseCategoryRepository purchaseCategoryRepository,
                        PurchaseRepository purchaseRepository,
                        String collectionName,
                        MilvusServiceClient milvusClient,
                        EmbeddingsModel embeddingsModel,
                        ChatModel chatModel,
                        ObjectMapper objectMapper
    ) {
        this.purchaseCategoryRepository = purchaseCategoryRepository;
        this.purchaseRepository = purchaseRepository;
        this.collectionName = collectionName;
        this.milvusClient = milvusClient;
        this.embeddingsModel = embeddingsModel;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(value = "accounting-tx", readOnly = true)
    @Secured({"ROLE_USER"})
    public List<PurchasesApi.Purchase> classifyPurchases(List<String> purchaseIdList) {
        final List<PurchaseCategory> categories = purchaseCategoryRepository.findAll(SecurityUtils.getCurrentPerson());

        final AtomicInteger categoryIndex = new AtomicInteger(1);
        final AtomicInteger purchaseIndex = new AtomicInteger(1);

        final Map<PurchaseCategoryId, String> categoryIdMapping = categories.stream()
                .collect(Collectors.toMap(PurchaseCategory::categoryId,
                        pc -> "category%d".formatted(categoryIndex.getAndIncrement())));
        final Map<String, PurchaseCategoryId> categoryIdReverseMapping = categoryIdMapping.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

        final Map<String, List<PurchaseCategory>> categoryByName =
                categories.stream().collect(Collectors.groupingBy(c -> c.name().toLowerCase()));

        final Function<PurchaseCategoryId, PurchaseCategory> findById = purchaseCategoryRepository::findById;

        final Function<String, PurchaseCategory> lookByName = name ->
                Optional.ofNullable(categoryByName.get(name.toLowerCase()))
                        .map(candidates -> candidates.get(0))
                        .orElse(null);
        try {
            final List<Purchase> purchases = loadPurchases(purchaseIdList);
            final Map<PurchaseId, Purchase> purchaseMap = purchases.stream()
                    .collect(Collectors.toMap(Purchase::purchaseId, p -> p));
            final Map<PurchaseId, String> purchaseIdMapping = purchases.stream()
                    .collect(Collectors.toMap(Purchase::purchaseId,
                            pc -> "purchase%d".formatted(purchaseIndex.getAndIncrement())));
            final Map<String, PurchaseId> purchaseIdReverseMapping = purchaseIdMapping.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));


            // Prepare list of possible categories for final prompt
            final String categoriesContext = "List of possible categories: %s".formatted(
                    objectMapper.writeValueAsString(
                            categories.stream()
                                    .map(c -> new CategoryData(c.name(), categoryIdMapping.get(c.categoryId())))
                                    .toList()));

            // Prepare context for final prompt
            final String context = prepareContext(purchases);
            final String purchasesToClassify = objectMapper.writeValueAsString(purchases.stream()
                    .map(p -> new IdNameOfPurchase(purchaseIdMapping.get(p.purchaseId()), p.name()))
                    .toList());

            // The final prompt
            final String prompt = ragPrompt(context, categoriesContext,
                    purchaseAssignCategoriesQuestion(purchasesToClassify));


            final Function<String, PurchaseCategory> thinking = name ->
                    chatModel.chat(ragPrompt(categoriesContext, suggestParentCategory(name)))
                            .flatMap(this::parseCategoryAnswer)
                            .findFirst()
                            .map(CategoryData::id)
                            .map(categoryIdReverseMapping::get)
                            .map(findById)
                            .orElse(null);

            chatModel.chat(prompt)
                    .flatMap(this::parseCategoriesAnswer)
                    .forEach(answer -> {
                        final Purchase p = purchaseMap.get(purchaseIdReverseMapping.get(answer.purchaseId()));
                        if (p == null) return;

                        PurchaseCategory targetCategory = findById.apply(categoryIdReverseMapping.get(answer.categoryId()));
                        if (targetCategory == null && answer.categoryName() != null && !answer.categoryName().isBlank()) {
                            targetCategory = Optional.ofNullable(lookByName.apply(answer.categoryName()))
                                    .orElse(thinking.apply(answer.categoryName));
                        }

                        if (targetCategory != null) {
                            p.assignCategory(targetCategory);
                        }
                    });

            return new PurchaseAssembler().toDTOList(purchases);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String prepareContext(List<Purchase> purchases) {
        return purchases.parallelStream()
                .map(purchase -> Templates.purchaseSearchQuery(purchase.name()))
                .flatMap(this::vectorSearch)
                .distinct()
                .collect(Collectors.joining("\r\n"));
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

    private Stream<String> vectorSearch(String query) {
        Float[] embedding = embeddingsModel.embed(query);
        SearchResults searchResults = milvusClient.search(SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName(F_VECTOR)
                .withOutFields(List.of(F_PK, F_LAST_MODIFIED, F_TEXT))
                .withVectors(List.of(Arrays.asList(embedding)))
                .withTopK(3)
                .build()).getData();
        SearchResultsWrapper wrapperSearch = new SearchResultsWrapper(searchResults.getResults());

        List<?> relatedText = wrapperSearch.getFieldWrapper(F_TEXT).getFieldData();
        return relatedText.stream().map(Object::toString);
    }

    private Stream<PurchaseCategoryData> parseCategoriesAnswer(String answer) {
        try {
            return Arrays.stream(objectMapper.readValue(answer, PurchaseCategoryData[].class));
        } catch (JsonProcessingException e) {
            log.error("Error when reading JSON from string: {}", answer);
            return Stream.empty();
        }
    }

    private Stream<CategoryData> parseCategoryAnswer(String answer) {
        try {
            return Stream.of(objectMapper.readValue(answer, CategoryData.class));
        } catch (JsonProcessingException e) {
            log.error("Error when reading JSON from string: {}", answer);
            return Stream.empty();
        }
    }

    record CategoryData(String name, String id) {
    }

    record PurchaseCategoryData(String purchaseId, String categoryId, String categoryName) {
    }

    record IdNameOfPurchase(String purchaseId, String purchaseName) {
    }

}
