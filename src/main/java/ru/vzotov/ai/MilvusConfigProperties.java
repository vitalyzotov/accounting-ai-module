package ru.vzotov.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MilvusConfigProperties {

    private String host;
    private Integer port;
    private Integer dimension;
    private String collectionName;
}
