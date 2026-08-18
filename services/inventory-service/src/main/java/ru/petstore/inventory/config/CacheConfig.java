package ru.petstore.inventory.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.reference.ReferenceCaches;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.inventory.repository.ReservationStatusRepository;
import ru.petstore.inventory.repository.WarehouseRepository;
import ru.petstore.inventory.service.ReferenceType;

@Configuration
public class CacheConfig {

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> warehouseCache(WarehouseRepository repository) {
        return ReferenceCaches.of("warehouses", repository::findAll);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> reservationStatusCache(
            ReservationStatusRepository repository) {
        return ReferenceCaches.of("reservation-statuses", repository::findAll);
    }

    @Bean
    public ReferenceDataService referenceDataService(
            Map<String, RefreshableReferenceCache<String, ReferenceItem>> caches) {
        return new ReferenceDataService(ReferenceType.values(), caches);
    }
}
