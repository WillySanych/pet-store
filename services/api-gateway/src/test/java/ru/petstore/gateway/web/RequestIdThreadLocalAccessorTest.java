package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

class RequestIdThreadLocalAccessorTest {

    @BeforeAll
    static void enableContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Аксессор подхватывается через META-INF/services")
    void accessorIsLoadedByServiceLoader() {
        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                .anyMatch(accessor -> RequestTracingFilter.MDC_KEY.equals(accessor.key()));
    }

    @Test
    @DisplayName("Идентификатор из контекста Reactor виден в MDC на другом потоке")
    void requestIdReachesMdcAfterAThreadHop() {
        var seenInMdc = new AtomicReference<String>();

        Mono.just("request")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(ignored -> seenInMdc.set(MDC.get(RequestTracingFilter.MDC_KEY)))
                .contextWrite(Context.of(RequestTracingFilter.MDC_KEY, "abc"))
                .block();

        assertThat(seenInMdc.get()).isEqualTo("abc");
    }

    @Test
    @DisplayName("После обработки идентификатор не остаётся в MDC потока")
    void mdcIsClearedAfterTheHop() {
        var mdcAfterHop = new AtomicReference<String>();

        Mono.just("request")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(ignored -> mdcAfterHop.set("hop"))
                .contextWrite(Context.of(RequestTracingFilter.MDC_KEY, "abc"))
                .block();

        assertThat(mdcAfterHop.get()).isEqualTo("hop");
        assertThat(MDC.get(RequestTracingFilter.MDC_KEY)).isNull();
    }
}
