package ru.vzotov.ai.interfaces.facade;

import ru.vzotov.accounting.interfaces.purchases.PurchasesApi;

import java.util.List;

public interface AIFacade {
    List<PurchasesApi.Purchase> classifyPurchases(List<String> purchaseId);
}
