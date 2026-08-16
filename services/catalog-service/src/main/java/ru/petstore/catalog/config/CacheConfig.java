package ru.petstore.catalog.config;

import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.catalog.domain.ReferenceEntity;
import ru.petstore.catalog.repository.BrandRepository;
import ru.petstore.catalog.repository.CategoryRepository;
import ru.petstore.catalog.repository.SpeciesRepository;
import ru.petstore.catalog.service.ReferenceItem;
import ru.petstore.common.cache.RefreshableReferenceCache;

/**
 * The three reference caches of this service. {@code ReferenceCacheRegistry} from
 * {@code common-core} picks up every cache bean, warms it up, refreshes it and binds its metrics.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> categoryCache(CategoryRepository repository) {
        return referenceCache("categories", repository);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> speciesCache(SpeciesRepository repository) {
        return referenceCache("species", repository);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> brandCache(BrandRepository repository) {
        return referenceCache("brands", repository);
    }

    private static RefreshableReferenceCache<String, ReferenceItem> referenceCache(
            String name, JpaRepository<? extends ReferenceEntity, Long> repository) {
        return new RefreshableReferenceCache<>(name, () -> repository.findAll().stream()
                .collect(Collectors.toMap(
                        ReferenceEntity::getCode,
                        entity -> new ReferenceItem(entity.getId(), entity.getCode(), entity.getName()))));
    }
}
