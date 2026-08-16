package ru.petstore.catalog.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import ru.petstore.catalog.service.ProductService;
import ru.petstore.catalog.web.dto.ProductFilterRequest;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.overload.OverloadProtection;
import ru.petstore.common.overload.OverloadedException;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService products;

    @Test
    @DisplayName("Исчерпанный bulkhead отклоняет листинг, не доходя до сервиса и БД")
    void listIsRejectedWhenTheBulkheadIsFull() {
        var full = new OverloadProtection(0, new ServiceMetrics(new SimpleMeterRegistry()));
        var controller = new ProductController(products, full);

        assertThatThrownBy(() ->
                controller.list(new ProductFilterRequest(null, null, null, null), PageRequest.of(0, 20)))
                .isInstanceOf(OverloadedException.class);

        verifyNoInteractions(products);
    }
}
