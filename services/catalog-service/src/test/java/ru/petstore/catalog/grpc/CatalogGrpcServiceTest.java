package ru.petstore.catalog.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.petstore.catalog.service.ProductService;
import ru.petstore.catalog.service.ProductSummary;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.overload.OverloadProtection;
import ru.petstore.proto.catalog.GetProductsRequest;
import ru.petstore.proto.catalog.GetProductsResponse;

@ExtendWith(MockitoExtension.class)
class CatalogGrpcServiceTest {

    @Mock
    private ProductService products;

    @Mock
    private StreamObserver<GetProductsResponse> observer;

    private CatalogGrpcService service() {
        return service(64);
    }

    private CatalogGrpcService service(int maxConcurrent) {
        return new CatalogGrpcService(products,
                new OverloadProtection(maxConcurrent, new ServiceMetrics(new SimpleMeterRegistry())));
    }

    private Status.Code errorCode() {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(error.getValue()).isInstanceOf(StatusRuntimeException.class);
        return ((StatusRuntimeException) error.getValue()).getStatus().getCode();
    }

    private GetProductsResponse capturedResponse() {
        ArgumentCaptor<GetProductsResponse> response = ArgumentCaptor.forClass(GetProductsResponse.class);
        verify(observer).onNext(response.capture());
        verify(observer).onCompleted();
        return response.getValue();
    }

    @Test
    @DisplayName("Цена уезжает строкой без потери копеек")
    void priceIsSentAsStringWithoutLosingCents() {
        UUID id = UUID.randomUUID();
        when(products.getProductSummaries(List.of(id)))
                .thenReturn(List.of(new ProductSummary(id, "Корм", new BigDecimal("2499.90"), true)));

        service().getProducts(
                GetProductsRequest.newBuilder().addProductIds(id.toString()).build(), observer);

        assertThat(capturedResponse().getProductsList())
                .singleElement()
                .satisfies(product -> {
                    assertThat(product.getId()).isEqualTo(id.toString());
                    assertThat(product.getPrice()).isEqualTo("2499.90");
                    assertThat(product.getActive()).isTrue();
                });
    }

    @Test
    @DisplayName("Ненайденный товар просто отсутствует в ответе")
    void missingProductIsOmittedFromResponse() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(products.getProductSummaries(List.of(found, missing)))
                .thenReturn(List.of(new ProductSummary(found, "Корм", new BigDecimal("10.00"), true)));

        service().getProducts(GetProductsRequest.newBuilder()
                .addProductIds(found.toString())
                .addProductIds(missing.toString())
                .build(), observer);

        assertThat(capturedResponse().getProductsList()).hasSize(1);
        verify(observer, never()).onError(any());
    }

    @Test
    @DisplayName("Битый идентификатор — INVALID_ARGUMENT, а не пустая выдача")
    void malformedIdIsRejected() {
        service().getProducts(
                GetProductsRequest.newBuilder().addProductIds("not-a-uuid").build(), observer);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(error.getValue()).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));

        verify(observer, never()).onNext(any());
        verifyNoInteractions(products);
    }

    @Test
    @DisplayName("Пустой запрос отдаёт пустой ответ, а не ошибку")
    void emptyRequestReturnsEmptyResponse() {
        when(products.getProductSummaries(List.of())).thenReturn(List.of());

        service().getProducts(GetProductsRequest.newBuilder().build(), observer);

        assertThat(capturedResponse().getProductsList()).isEmpty();
    }

    @Test
    @DisplayName("Один и тот же id дважды — один запрос и один товар в ответе")
    void duplicateIdsAreCollapsedBeforeTheQuery() {
        UUID id = UUID.randomUUID();
        when(products.getProductSummaries(List.of(id)))
                .thenReturn(List.of(new ProductSummary(id, "Корм", new BigDecimal("10.00"), true)));

        service().getProducts(GetProductsRequest.newBuilder()
                .addProductIds(id.toString())
                .addProductIds(id.toString())
                .build(), observer);

        assertThat(capturedResponse().getProductsList()).hasSize(1);
        verify(products).getProductSummaries(List.of(id));
        verify(observer, never()).onError(any());
    }

    @Test
    @DisplayName("Недоступная БД — UNAVAILABLE, а не UNKNOWN из глубины драйвера")
    void databaseFailureIsReportedAsUnavailable() {
        when(products.getProductSummaries(any()))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        service().getProducts(GetProductsRequest.newBuilder()
                .addProductIds(UUID.randomUUID().toString()).build(), observer);

        assertThat(errorCode()).isEqualTo(Status.Code.UNAVAILABLE);
        verify(observer, never()).onNext(any());
    }

    @Test
    @DisplayName("Прочая ошибка сервиса — INTERNAL без утечки стектрейса наружу")
    void unexpectedFailureIsReportedAsInternal() {
        when(products.getProductSummaries(any())).thenThrow(new IllegalStateException("boom"));

        service().getProducts(GetProductsRequest.newBuilder()
                .addProductIds(UUID.randomUUID().toString()).build(), observer);

        assertThat(errorCode()).isEqualTo(Status.Code.INTERNAL);
        verify(observer, never()).onNext(any());
    }

    @Test
    @DisplayName("Исчерпанный bulkhead — RESOURCE_EXHAUSTED, аналог 429 по HTTP")
    void overloadIsReportedAsResourceExhausted() {
        service(0).getProducts(GetProductsRequest.newBuilder()
                .addProductIds(UUID.randomUUID().toString()).build(), observer);

        assertThat(errorCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        verify(observer, never()).onNext(any());
        verifyNoInteractions(products);
    }
}
