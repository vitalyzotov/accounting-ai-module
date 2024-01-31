package ru.vzotov.ai.domain;

import ru.vzotov.purchase.domain.model.Purchase;

public class Templates {
    private static final String PURCHASE_HAS_CATEGORY = "Purchase '%s' has category '%s' with id '%s'.";
    private static final String PURCHASE_SEARCH_QUERY = "Purchase '%s' has category";
    private static final String PURCHASE_ASSIGN_CATEGORY = """
            Please answer what category has purchase '%s'. 
            As an answer give JSON object with category name in the "name" field and category id in the "id" field. 
            Use only the categories that are listed in the possible_answers.
            If the context doesn't provide useful information, consider which of the possible categories in possible_answers best fits that purchase, and output it as a result.
            Your response will be used as an input string for the JSON parser, so form it strictly as a JSON response, don't give any explanations or comments.
            """;
    private static final String SUGGEST_PARENT_CATEGORY = """
            Please answer to which category from the context in the meaning of the name the subcategory with name `%s` best fits into.
            As an answer give JSON object with category name in the "name" field and category id in the "id" field.
            Use only the categories that are listed in the context.
            Your response will be used as an input string for the JSON parser, so form it strictly as a JSON response, don't give any explanations or comments.
            """;
    private static final String RAG_PROMPT_BOUNDED = """
            Используй следующие части контекста, чтобы ответить на вопрос в конце. Если ты не знаешь ответа, просто скажи, что не знаешь, не пытайся придумать ответ.
            <context>
            %s
            </context>
            Список возможных ответов указан в possible_answers: 
            <possible_answers>
            %s
            </possible_answers>
                        
            Question: %s
            Полезный ответ:
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

    public static String ragPrompt(String context, String possibleAnswers, String question) {
        return RAG_PROMPT_BOUNDED.formatted(context, possibleAnswers, question);
    }

    public static String purchaseAssignCategoryQuestion(Purchase purchase) {
        return PURCHASE_ASSIGN_CATEGORY.formatted(purchase.name());
    }

    public static String suggestParentCategory(String subcategory) {
        return SUGGEST_PARENT_CATEGORY.formatted(subcategory);
    }
}
