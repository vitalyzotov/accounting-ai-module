package ru.vzotov.ai;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchasesConfigProperties {
    @Min(1)
    Integer partitionSize = 1000;

    Integer initialDelay = 3000;

    Integer delay = 3600000;
}
