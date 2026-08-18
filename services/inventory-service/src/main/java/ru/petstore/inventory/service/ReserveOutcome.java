package ru.petstore.inventory.service;

import java.util.List;
import java.util.UUID;

public record ReserveOutcome(boolean reserved, List<UUID> unavailableProductIds) {

    public static ReserveOutcome held() {
        return new ReserveOutcome(true, List.of());
    }

    public static ReserveOutcome refused(List<UUID> unavailable) {
        return new ReserveOutcome(false, List.copyOf(unavailable));
    }
}
