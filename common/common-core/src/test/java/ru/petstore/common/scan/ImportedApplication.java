package ru.petstore.common.scan;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import ru.petstore.common.autoconfigure.CommonCoreAutoConfiguration;

/**
 * The same with an explicit {@code @Import}: the configuration is then processed eagerly rather
 * than deferred, and {@code @ConditionalOnMissingBean} could stop holding.
 */
@SpringBootApplication
@ComponentScan(basePackages = "ru.petstore.common")
@Import(CommonCoreAutoConfiguration.class)
public class ImportedApplication {
}
