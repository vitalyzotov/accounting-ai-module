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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ru.vzotov.ai.domain.Templates.ragPrompt;
import static ru.vzotov.ai.domain.Templates.suggestParentCategory;

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
    public List<PurchasesApi.Purchase> classifyPurchases(List<String> purchaseId) {
        List<PurchaseCategory> categories = purchaseCategoryRepository.findAll(SecurityUtils.getCurrentPerson());
        try {
            String categoriesContext = "List of possible categories: %s".formatted(
                    objectMapper.writeValueAsString(
                            categories.stream()
                                    .map(c -> new CategoryData(c.name(), c.categoryId().value()))
                                    .toList()));

            List<Purchase> purchases = purchaseId.stream()
                    .filter(Objects::nonNull)
                    .map(PurchaseId::new)
                    .map(purchaseRepository::find)
                    .filter(Objects::nonNull)
                    .toList();

            for (Purchase p : purchases) {
                String context = vectorSearch(Templates.purchaseSearchQuery(p)).collect(Collectors.joining("\r\n"));


                String prompt = ragPrompt(context, categoriesContext, Templates.purchaseAssignCategoryQuestion(p));
                chatModel.chat(prompt)
                        .flatMap(this::parseCategoryAnswer)
                        .findFirst()
                        .map(cat -> Optional.ofNullable(purchaseCategoryRepository.findById(new PurchaseCategoryId(cat.id)))
                                .orElseGet(() ->
                                        chatModel.chat(ragPrompt(categoriesContext, suggestParentCategory(cat.name())))
                                                .flatMap(this::parseCategoryAnswer)
                                                .findFirst()
                                                .map(CategoryData::id)
                                                .map(PurchaseCategoryId::new)
                                                .map(purchaseCategoryRepository::findById)
                                                .orElse(null)
                                ))
                        .ifPresent(p::assignCategory);
            }

            return new PurchaseAssembler().toDTOList(purchases);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
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

    @NotNull
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
}
