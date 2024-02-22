package ru.vzotov.ai.interfaces.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import ru.vzotov.accounting.interfaces.purchases.PurchasesApi;
import ru.vzotov.ai.AIModuleProperties;
import ru.vzotov.ai.interfaces.facade.AIFacade;

import java.util.List;

@ConditionalOnProperty(prefix = AIModuleProperties.PREFIX, name = "enabled")
@RestController
@RequestMapping("/accounting/purchases")
@CrossOrigin
public class PurchasesAiController {

    private final AIFacade facade;

    public PurchasesAiController(AIFacade facade) {
        this.facade = facade;
    }

    @PatchMapping
    public List<PurchasesApi.Purchase> classifyPurchases(@RequestBody ClassifyPurchasesRequest request) {
        return facade.classifyPurchases(request.purchaseId());
    }

    public record ClassifyPurchasesRequest(List<String> purchaseId) {

    }


}
