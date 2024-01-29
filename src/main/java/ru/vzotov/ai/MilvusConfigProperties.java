package ru.vzotov.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("accounting.ai.milvus")
public class MilvusConfigProperties {

    private String host;
    private final int port;

    public MilvusConfigProperties(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }
}
