package ru.petstore.customer.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.reference.ReferenceCaches;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.customer.repository.CityRepository;
import ru.petstore.customer.repository.CustomerStatusRepository;
import ru.petstore.customer.service.ReferenceType;

/**
 * The two reference caches of this service. {@code ReferenceCacheRegistry} from
 * {@code common-core} picks up every cache bean, warms it up, refreshes it and binds its metrics.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> cityCache(CityRepository repository) {
        return ReferenceCaches.of("cities", repository::findAll);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> customerStatusCache(
            CustomerStatusRepository repository) {
        return ReferenceCaches.of("customer-statuses", repository::findAll);
    }

    @Bean
    public ReferenceDataService referenceDataService(
            Map<String, RefreshableReferenceCache<String, ReferenceItem>> caches) {
        return new ReferenceDataService(ReferenceType.values(), caches);
    }
}
