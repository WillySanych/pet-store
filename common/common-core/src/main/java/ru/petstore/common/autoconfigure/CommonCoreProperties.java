package ru.petstore.common.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.petstore.common.web.RequestTracingFilter;

/** The {@code petstore.*} properties read by the auto-configuration. */
@ConfigurationProperties(prefix = "petstore")
public class CommonCoreProperties {

    private final Tracing tracing = new Tracing();
    private final Metrics metrics = new Metrics();
    private final Overload overload = new Overload();
    private final Scheduler scheduler = new Scheduler();

    public Tracing getTracing() {
        return tracing;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Overload getOverload() {
        return overload;
    }

    public Scheduler getScheduler() {
        return scheduler;
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

    public static class Metrics {
        private String excludePrefix = "/actuator";

        public String getExcludePrefix() {
            return excludePrefix;
        }

        public void setExcludePrefix(String excludePrefix) {
            this.excludePrefix = excludePrefix;
        }
    }

    public static class Overload {

        private int maxConcurrent = 64;

        private Duration maxWait = Duration.ofMillis(50);

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }
    }

    public static class Scheduler {
        /** The ShedLock table. */
        private String tableName = "shedlock";

        /** How long a lock is held when the pod dies mid-task. */
        private String defaultLockAtMostFor = "PT10M";

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getDefaultLockAtMostFor() {
            return defaultLockAtMostFor;
        }

        public void setDefaultLockAtMostFor(String defaultLockAtMostFor) {
            this.defaultLockAtMostFor = defaultLockAtMostFor;
        }
    }
}
