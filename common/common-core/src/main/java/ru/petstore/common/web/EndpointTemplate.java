package ru.petstore.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The metric label of a request: the path template Spring matched, never the actual URI.
 */
public final class EndpointTemplate {

    private static final String UNKNOWN = "unknown";

    private EndpointTemplate() {
    }

    public static String of(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : UNKNOWN;
    }
}
