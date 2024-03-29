package ru.vzotov.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Getter
@Setter
@ConfigurationProperties(prefix = AIModuleProperties.PREFIX)
public class AIModuleProperties {
    public static final String PREFIX = "accounting.ai";

    Boolean enabled = true;

    ModelType modelType = ModelType.GIGACHAT;

    @NestedConfigurationProperty
    PgVectorConfigProperties pgvector;

    @NestedConfigurationProperty
    PurchasesConfigProperties purchases;
}
