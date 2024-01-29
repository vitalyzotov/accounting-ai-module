package ru.vzotov.ai.interfaces.facade.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gigachat.v1.ChatServiceGrpc;
import gigachat.v1.Gigachatv1;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.annotation.Secured;
import org.springframework.transaction.annotation.Transactional;
import ru.vzotov.accounting.infrastructure.security.SecurityUtils;
import ru.vzotov.accounting.interfaces.purchases.PurchasesApi;
import ru.vzotov.accounting.interfaces.purchases.facade.impl.assembler.PurchaseAssembler;
import ru.vzotov.ai.application.EmbeddingsClient;
import ru.vzotov.ai.domain.PurchaseCategoriesCollection;
import ru.vzotov.ai.domain.Templates;
import ru.vzotov.ai.interfaces.facade.AIFacade;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategory;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryRepository;
import ru.vzotov.purchase.domain.model.Purchase;
import ru.vzotov.purchase.domain.model.PurchaseId;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class AIFacadeImpl implements AIFacade, PurchaseCategoriesCollection {
    private static final Logger log = LoggerFactory.getLogger(AIFacadeImpl.class);

    private final PurchaseCategoryRepository purchaseCategoryRepository;
    private final PurchaseRepository purchaseRepository;
    private final String collectionName;
    private final MilvusServiceClient milvusClient;
    private final EmbeddingsClient embeddingsClient;
    private final ChatServiceGrpc.ChatServiceBlockingStub gigachat;
    private final ObjectMapper objectMapper;

    public AIFacadeImpl(PurchaseCategoryRepository purchaseCategoryRepository,
                        PurchaseRepository purchaseRepository,
                        String collectionName,
                        MilvusServiceClient milvusClient,
                        EmbeddingsClient embeddingsClient, ChatServiceGrpc.ChatServiceBlockingStub gigachat, ObjectMapper objectMapper
    ) {
        this.purchaseCategoryRepository = purchaseCategoryRepository;
        this.purchaseRepository = purchaseRepository;
        this.collectionName = collectionName;
        this.milvusClient = milvusClient;
        this.embeddingsClient = embeddingsClient;
        this.gigachat = gigachat;
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
                String expression = Templates.purchaseSearchQuery(p);
                Float[] embedding = embeddingsClient.embed(expression);
                SearchResults searchResults = milvusClient.search(SearchParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withVectors(List.of(Arrays.asList(embedding)))
                        .withTopK(3)
                        .build()).getData();
                SearchResultsWrapper wrapperSearch = new SearchResultsWrapper(searchResults.getResults());
                List<?> relatedText = wrapperSearch.getFieldWrapper(F_TEXT).getFieldData();
                String context = "%s\r\n%s".formatted(
                        relatedText.stream().map(Object::toString).collect(Collectors.joining("\r\n")),
                        categoriesContext);

                String prompt = Templates.ragPrompt(context, Templates.purchaseAssignCategoryQuestion(p));
                Gigachatv1.ChatResponse chat = gigachat.chat(Gigachatv1.ChatRequest.newBuilder()
                        .setModel("GigaChat:latest")
                        .addMessages(Gigachatv1.Message.newBuilder()
                                .setRole("user")
                                .setContent(prompt).build())
                        .build());
                chat.getAlternativesList().stream().map(alt -> alt.getMessage().getContent())
                        .forEach(s -> log.debug("Answer: {}", s));

            }

            return new PurchaseAssembler().toDTOList(purchases);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    record CategoryData(String name, String id) {
    }
}
