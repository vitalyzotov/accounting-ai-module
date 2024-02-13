package ru.vzotov.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
public class MilvusConfigProperties {

    private String host;
    private Integer port;
    private Integer dimension;
    private String collectionName;
}
