package ru.petstore.common.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.petstore.common.web.RequestTracingFilter;

/**
 * The {@code petstore.*} properties read by the auto-configuration.
 */
@ConfigurationProperties(prefix = "petstore")
public class CommonCoreProperties {

    private final Tracing tracing = new Tracing();
    private final Overload overload = new Overload();

    public Tracing getTracing() {
        return tracing;
    }

    public Overload getOverload() {
        return overload;
    }

    public static class Tracing {
        private String headerName = RequestTracingFilter.REQUEST_ID_HEADER;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }
    }

    public static class Overload {
        private int maxConcurrent = 64;

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }
    }

}
