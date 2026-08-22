package ru.petstore.gateway.web;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

public class RequestIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
        return RequestTracingFilter.MDC_KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(RequestTracingFilter.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(RequestTracingFilter.MDC_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(RequestTracingFilter.MDC_KEY);
    }
}
