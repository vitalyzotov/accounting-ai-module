package ru.vzotov.ai;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchasesConfigProperties {
    @Min(1)
    Integer partitionSize = 1000;
}
