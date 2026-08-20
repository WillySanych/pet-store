package ru.petstore.order.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.reference.ReferenceCaches;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.order.repository.DeliveryTypeRepository;
import ru.petstore.order.repository.OrderStatusRepository;
import ru.petstore.order.repository.PaymentMethodRepository;
import ru.petstore.order.service.ReferenceType;

@Configuration
public class CacheConfig {

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> orderStatusCache(OrderStatusRepository repository) {
        return ReferenceCaches.of("order-statuses", repository::findAll);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> deliveryTypeCache(DeliveryTypeRepository repository) {
        return ReferenceCaches.of("delivery-types", repository::findAll);
    }

    @Bean
    public RefreshableReferenceCache<String, ReferenceItem> paymentMethodCache(PaymentMethodRepository repository) {
        return ReferenceCaches.of("payment-methods", repository::findAll);
    }

    @Bean
    public ReferenceDataService referenceDataService(
            Map<String, RefreshableReferenceCache<String, ReferenceItem>> caches) {
        return new ReferenceDataService(ReferenceType.values(), caches);
    }
}
