package ru.vzotov.ai.interfaces.facade.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.vzotov.accounting.infrastructure.security.User;
import ru.vzotov.accounting.interfaces.common.CommonApi;
import ru.vzotov.accounting.interfaces.purchases.PurchasesApi;
import ru.vzotov.ai.AIModule;
import ru.vzotov.ai.application.PurchaseCategoryProcessor;
import ru.vzotov.ai.interfaces.facade.AIFacade;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategory;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryId;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryRepository;
import ru.vzotov.cashreceipt.domain.model.ReceiptId;
import ru.vzotov.domain.model.Money;
import ru.vzotov.langchain4j.gigachat.GigachatScope;
import ru.vzotov.person.domain.model.PersonId;
import ru.vzotov.purchase.domain.model.Purchase;
import ru.vzotov.purchase.domain.model.PurchaseId;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GIGACHAT_CLIENT_ID", matches = ".+")
public class AIFacadeImplIT {
    private static final String GIGACHAT_CLIENT_ID = System.getenv("GIGACHAT_CLIENT_ID");
    private static final String GIGACHAT_CLIENT_SECRET = System.getenv("GIGACHAT_CLIENT_SECRET");
    private static final String GIGACHAT_SCOPE = Optional.ofNullable(System.getenv("GIGACHAT_SCOPE"))
            .orElse(GigachatScope.GIGACHAT_API_PERS.name());
    private static final String MILVUS_HOST = System.getenv("MILVUS_HOST");
    private static final String MILVUS_PORT = System.getenv("MILVUS_PORT");

    public static final PersonId U_1 = new PersonId("U1");

    public static final PurchaseCategoryId C_1 = new PurchaseCategoryId("C1");
    public static final PurchaseCategory CATEGORY_1 = new PurchaseCategory(C_1, U_1, "category 1");

    public static final PurchaseCategoryId C_2 = new PurchaseCategoryId("C2");
    public static final PurchaseCategory CATEGORY_2 = new PurchaseCategory(C_2, U_1, "category 2");

    public static final PurchaseId P_1 = new PurchaseId("P1");
    public static final MockedPurchase PURCHASE_1 = new MockedPurchase(P_1, U_1, "purchase 1",
            LocalDateTime.of(2000, 1, 1, 0, 0),
            Money.rubles(10), BigDecimal.valueOf(1), null, CATEGORY_1);
    public static final PurchaseId P_2 = new PurchaseId("P2");
    public static final MockedPurchase PURCHASE_2 = new MockedPurchase(P_2, U_1, "purchase 2",
            LocalDateTime.of(2000, 2, 1, 0, 0),
            Money.rubles(15), BigDecimal.valueOf(2), null, CATEGORY_2);

    public static final PurchaseId P_3 = new PurchaseId("P3");
    public static final MockedPurchase PURCHASE_3 = new MockedPurchase(P_3, U_1, "purchase 1",
            LocalDateTime.of(2000, 1, 1, 0, 0),
            Money.rubles(10), BigDecimal.valueOf(1));

    public static final PurchaseId P_4 = new PurchaseId("P4");
    public static final MockedPurchase PURCHASE_4 = new MockedPurchase(P_4, U_1, "purchase 2",
            LocalDateTime.of(2000, 2, 1, 0, 0),
            Money.rubles(15), BigDecimal.valueOf(2));


    private PurchaseRepository purchaseRepository;
    private PurchaseCategoryRepository purchaseCategoryRepository;

    ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(AIModule.class));

    @BeforeEach
    public void setUp() {

        purchaseRepository = Mockito.mock(PurchaseRepository.class);
        purchaseCategoryRepository = Mockito.mock(PurchaseCategoryRepository.class);

        Mockito.when(purchaseCategoryRepository.findAll(Mockito.any()))
                .thenReturn(List.of(CATEGORY_1, CATEGORY_2));

        Mockito.when(purchaseRepository.find(P_1))
                .thenReturn(PURCHASE_1);
        Mockito.when(purchaseRepository.find(P_2))
                .thenReturn(PURCHASE_2);
        Mockito.when(purchaseRepository.find(P_3))
                .thenReturn(PURCHASE_3);
        Mockito.when(purchaseRepository.find(P_4))
                .thenReturn(PURCHASE_4);

    }

    @Test
    void should_classify_purchases() {
        contextRunner
                .withPropertyValues(
                        "langchain4j.gigachat.chat-model.client-id=" + GIGACHAT_CLIENT_ID,
                        "langchain4j.gigachat.chat-model.client-secret=" + GIGACHAT_CLIENT_SECRET,
                        "langchain4j.gigachat.chat-model.scope=" + GIGACHAT_SCOPE,
                        "langchain4j.gigachat.embedding-model.client-id=" + GIGACHAT_CLIENT_ID,
                        "langchain4j.gigachat.embedding-model.client-secret=" + GIGACHAT_CLIENT_SECRET,
                        "langchain4j.gigachat.embedding-model.scope=" + GIGACHAT_SCOPE,
                        "accounting.ai.enabled=true",
                        "accounting.ai.milvus.host=" + MILVUS_HOST,
                        "accounting.ai.milvus.port=" + MILVUS_PORT,
                        "accounting.ai.milvus.dimension=1024",
                        "accounting.ai.milvus.collectionName=accountingIT"
                )
                .withBean("purchaseRepository", PurchaseRepository.class, () -> purchaseRepository)
                .withBean("purchaseCategoryRepository", PurchaseCategoryRepository.class, () -> purchaseCategoryRepository)
                .withBean("objectMapper", ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .run(context -> {
                    PurchaseCategoryProcessor processor = context.getBean(PurchaseCategoryProcessor.class);
                    processor.process(List.of(
                            purchaseRepository.find(P_1),
                            purchaseRepository.find(P_2)
                    ));

                    AIFacade facade = context.getBean(AIFacade.class);
                    createSecurityContext();

                    List<PurchasesApi.Purchase> purchases = facade.classifyPurchases(List.of(P_3.value(), P_4.value()));
                    assertThat(purchases).hasSize(2)
                            .containsExactlyInAnyOrder(
                                    new PurchasesApi.Purchase(P_3.value(), U_1.value(), null,
                                            PURCHASE_3.name(), PURCHASE_3.dateTime(), toMoney(PURCHASE_3.price()),
                                            PURCHASE_3.quantity().doubleValue(),
                                            C_1.value()),
                                    new PurchasesApi.Purchase(P_4.value(), U_1.value(), null,
                                            PURCHASE_4.name(), PURCHASE_4.dateTime(), toMoney(PURCHASE_4.price()),
                                            PURCHASE_4.quantity().doubleValue(),
                                            C_2.value())
                            );
                });
    }

    private static void createSecurityContext() {
        User principal = new User(U_1.value(), "", new SimpleGrantedAuthority(U_1.authority()), getAuthorities(new String[]{"USER"}, U_1.value()));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                principal.getPassword(),
                principal.getAuthorities()
        ));
        SecurityContextHolder.setContext(ctx);
    }

    private static CommonApi.Money toMoney(Money money) {
        return new CommonApi.Money(money.rawAmount(), money.currency().getCurrencyCode());
    }

    static class MockedPurchase extends Purchase {
        public MockedPurchase(PurchaseId purchaseId, PersonId owner, String name, LocalDateTime dateTime, Money price, BigDecimal quantity) {
            super(purchaseId, owner, name, dateTime, price, quantity);
            onCreate();
        }

        public MockedPurchase(PurchaseId purchaseId, PersonId owner, String name, LocalDateTime dateTime, Money price, BigDecimal quantity, ReceiptId receiptId, PurchaseCategory category) {
            super(purchaseId, owner, name, dateTime, price, quantity, receiptId, category);
            onCreate();
        }
    }

    static List<GrantedAuthority> getAuthorities(String[] roles, String person) {
        return Stream.concat(Stream.of(new PersonId(person).authority()), Arrays.stream(roles).map(a -> "ROLE_" + a))
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
