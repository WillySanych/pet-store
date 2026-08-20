package ru.petstore.order.client;

import java.util.List;
import java.util.UUID;

public record ReserveResult(boolean reserved, List<UUID> unavailableProductIds) {

    public static ReserveResult held() {
        return new ReserveResult(true, List.of());
    }

    public static ReserveResult refused(List<UUID> unavailable) {
        return new ReserveResult(false, List.copyOf(unavailable));
    }
}
