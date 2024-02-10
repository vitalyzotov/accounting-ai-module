package ru.vzotov.ai.domain;

import ru.vzotov.purchase.domain.model.Purchase;

public class Templates {

    public static String purchaseHasCategory(Purchase purchase) {
        return "Purchase '%s' has category '%s' with id '%s'."
                .formatted(purchase.name(), purchase.category().name(), purchase.category().categoryId().value());
    }

    public static String purchaseSearchQuery(String purchaseName) {
        return "Purchase '%s' has category"
                .formatted(purchaseName);
    }

    public static String ragPrompt(String context, String question) {
        return """
                Use the following pieces of context to answer the question at the end. If you don't know the answer, use null value.
                <context>
                %s
                </context>
                            
                Question: %s
                Useful answer:
                """.formatted(context, question);
    }

    public static String ragPrompt(String context, String possibleAnswers, String question) {
        return """
                Use the following pieces of context to answer the question at the end. If you don't know the answer, use null value.
                <context>
                %s
                </context>
                The list of possible response entries is specified in possible_answers:
                <possible_answers>
                %s
                </possible_answers>
                            
                Question: %s
                Useful answer:
                """.formatted(context, possibleAnswers, question);
    }

    public static String purchaseAssignCategoryQuestion(String purchaseName) {
        return """
                Please answer what category has purchase '%s'. 
                As an answer give JSON object with category name in the "name" field and category id in the "id" field. 
                Use only the categories that are listed in the possible_answers.
                If the context doesn't provide useful information, consider which of the possible categories in possible_answers best fits that purchase, and output it as a result.
                Your response will be used as an input string for the JSON parser, so form it strictly as a JSON response, don't give any explanations or comments.
                """.formatted(purchaseName);
    }

    public static String purchaseAssignCategoriesQuestion(String purchasesJson) {
        return """
                Please answer which categories the list of purchases belong to: `%s`
                Go through each purchase step by step and select a category for it.
                Try to use the categories that are listed in possible_answers.
                If the context doesn't provide useful information, as a last resort, propose your own category name for this purchase and specify <new> as its identifier.
                As an answer, provide a JSON array of this form:
                    
                [
                  {
                    "purchaseId": id_of_purchase,
                    "categoryId": id_of_category,
                    "categoryName": name_of_category
                  },
                  ...
                ]
                    
                Your response will be used as the input string for the JSON parser, so format it strictly as a JSON response, do not provide any explanations or comments.
                """.formatted(purchasesJson);
    }

    public static String suggestParentCategory(String subcategory) {
        return """
                Please answer to which category from the context in the meaning of the name the subcategory with name `%s` best fits into.
                As an answer give JSON object with category name in the "name" field and category id in the "id" field.
                Use only the categories that are listed in the context.
                Your response will be used as an input string for the JSON parser, so form it strictly as a JSON response, don't give any explanations or comments.
                """.formatted(subcategory);
    }
}
