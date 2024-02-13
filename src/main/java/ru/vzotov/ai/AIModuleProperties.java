package ru.vzotov.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Getter
@Setter
@ConfigurationProperties(prefix = AIModuleProperties.PREFIX)
public class AIModuleProperties {
    static final String PREFIX = "accounting.ai";

    @NestedConfigurationProperty
    MilvusConfigProperties milvus;

    @NestedConfigurationProperty
    PurchasesConfigProperties purchases;
}
