package ru.vzotov.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import ru.vzotov.accounting.domain.model.PersistentProperty;
import ru.vzotov.accounting.domain.model.PersistentPropertyId;
import ru.vzotov.accounting.domain.model.PersistentPropertyRepository;
import ru.vzotov.ai.ModelType;
import ru.vzotov.purchase.domain.model.Purchase;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.teeing;
import static ru.vzotov.purchases.domain.model.PurchaseSpecifications.updatedAfter;

/**
 * Indexes purchase categories for vector search.
 */
public class PurchaseCategoryIndexer {
    private static final Logger log = LoggerFactory.getLogger(PurchaseCategoryIndexer.class);

    private final ObjectMapper objectMapper;
    private final PurchaseRepository purchaseRepository;
    private final PersistentPropertyRepository propertyRepository;
    private final ModelType modelType;
    private final PurchaseCategoryProcessor processor;

    @Builder
    public PurchaseCategoryIndexer(
            ObjectMapper objectMapper,
            PurchaseRepository purchaseRepository,
            PersistentPropertyRepository propertyRepository,
            PurchaseCategoryProcessor processor,
            ModelType modelType
    ) {
        this.processor = Objects.requireNonNull(processor);
        this.objectMapper = objectMapper;
        this.purchaseRepository = purchaseRepository;
        this.propertyRepository = propertyRepository;
        this.modelType = Objects.requireNonNull(modelType);
    }

    private String systemPropertyName() {
        return "ai.purchases." + modelType;
    }

    /**
     * Date of the most recent known purchase
     */
    public Instant lastIndexedOn() {
        PersistentProperty prop = propertyRepository.findSystemProperty(systemPropertyName());
        if (prop == null) {
            return Instant.EPOCH;
        } else {
            try {
                PurchasesAIProperties props = objectMapper.readValue(prop.value(), PurchasesAIProperties.class);
                return props.lastIndexedOn();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void updateLastIndexedOn(Instant value) {
        final String systemPropertyName = systemPropertyName();
        PersistentProperty prop = Optional.ofNullable(propertyRepository.findSystemProperty(systemPropertyName))
                .orElseGet(() -> new PersistentProperty(PersistentPropertyId.nextId(), systemPropertyName));
        PurchasesAIProperties props = new PurchasesAIProperties(value);//todo: keep other properties
        try {
            prop.setValue(objectMapper.writeValueAsString(props));
            propertyRepository.store(prop);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Scheduled(initialDelayString = "#{@aiModuleProperties.purchases.initialDelay}",
            fixedDelayString = "#{@aiModuleProperties.purchases.delay}")
    @Transactional(value = "accounting-tx")
    public void doIndex() {
        log.info("Start indexing purchases");

        List<Purchase> purchases = purchaseRepository.findAll(updatedAfter(lastIndexedOn()));
        log.debug("There are {} new purchases since last indexing", purchases.size());

        BiFunction<List<Purchase>, Optional<Instant>, Instant> merger = (result, lastUpdated) -> {
            processor.process(result);
            lastUpdated.ifPresent(this::updateLastIndexedOn);
            return lastUpdated.orElse(null);
        };

        Instant updated = purchases.stream()
                .filter(purchase -> purchase != null && purchase.category() != null)
                .collect(teeing(
                        Collectors.toList(),
                        mapping(Purchase::updatedOn, Collectors.maxBy(Comparator.naturalOrder())),
                        merger
                ));

        log.info("Done indexing purchases, lastUpdated={}", updated);
    }

}
