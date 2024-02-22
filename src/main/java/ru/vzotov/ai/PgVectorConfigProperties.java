package ru.vzotov.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PgVectorConfigProperties {

    private String host = "localhost";
    private Integer port = 5432;
    private Integer dimension = 1024;
    private Integer indexListSize = 100;
    private String database = "accounting";
    private String user = "accounting";
    private String password = "accounting";
    private String table;
    private Boolean drop;
    private Boolean create;
}
