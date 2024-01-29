package ru.vzotov.ai.domain;

import ru.vzotov.purchase.domain.model.Purchase;

public class Templates {
    private static final String PURCHASE_HAS_CATEGORY = "Purchase '%s' has category '%s' with id '%s'.";
    private static final String PURCHASE_SEARCH_QUERY = "Purchase '%s' has category";
    private static final String PURCHASE_ASSIGN_CATEGORY = """
            Please answer what category has purchase '%s'. 
            As an answer give JSON object with category name in the "category" field and category id in the "id" field. 
            Use only the categories that are listed in the context. 
            """;
    private static final String RAG_PROMPT = """
            Используй следующие части контекста, чтобы ответить на вопрос в конце. Если ты не знаешь ответа, просто скажи, что не знаешь, не пытайся придумать ответ.
            <context>
            %s
            </context>
                        
            Question: %s
            Полезный ответ:
            """;

    public static String purchaseHasCategory(Purchase purchase) {
        return PURCHASE_HAS_CATEGORY.formatted(purchase.name(), purchase.category().name(), purchase.category().categoryId().value());
    }

    public static String purchaseSearchQuery(Purchase purchase) {
        return PURCHASE_SEARCH_QUERY.formatted(purchase.name());
    }

    public static String ragPrompt(String context, String question) {
        return RAG_PROMPT.formatted(context, question);
    }

    public static String purchaseAssignCategoryQuestion(Purchase purchase) {
        return PURCHASE_ASSIGN_CATEGORY.formatted(purchase.name());
    }
}
