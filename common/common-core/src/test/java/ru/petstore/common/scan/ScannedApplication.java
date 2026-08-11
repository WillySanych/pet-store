package ru.petstore.common.scan;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * A service whose component scan covers the {@code common-core} packages: the
 * {@code @RestControllerAdvice} is picked up by the scan, and the auto-configuration could
 * register the same bean a second time.
 */
@SpringBootApplication
@ComponentScan(basePackages = "ru.petstore.common")
public class ScannedApplication {
}
