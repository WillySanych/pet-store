package ru.petstore.catalog.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petstore.catalog.repository.BrandRepository;
import ru.petstore.catalog.repository.CategoryRepository;
import ru.petstore.catalog.repository.SpeciesRepository;
import ru.petstore.catalog.service.ReferenceType;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.reference.ReferenceCaches;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;

/**
 * The three reference caches of this service. {@code ReferenceCacheRegistry} from
 * {@code common-core} picks up every cache bean, warms it up, refreshes it and binds its metrics.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> categoryCache(CategoryRepository repository) {
        return ReferenceCaches.of("categories", repository::findAll);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> speciesCache(SpeciesRepository repository) {
        return ReferenceCaches.of("species", repository::findAll);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> brandCache(BrandRepository repository) {
        return ReferenceCaches.of("brands", repository::findAll);
    }

    @Bean
    public ReferenceDataService referenceDataService(
            Map<String, RefreshableReferenceCache<String, ReferenceItem>> caches) {
        return new ReferenceDataService(ReferenceType.values(), caches);
    }
}
