package ru.petstore.common.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.autoconfigure.CommonCoreProperties;

class SchedulerLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchedulerLockAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("Без датасорса блокировка не настраивается — хранить её негде")
    void lockProviderIsAbsentWithoutDataSource() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LockProvider.class));
    }

    @Test
    @DisplayName("С датасорсом LockProvider поднимается сам")
    void lockProviderIsRegisteredWithDataSource() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> assertThat(context).hasSingleBean(JdbcTemplateLockProvider.class));
    }

    @Test
    @DisplayName("Сервис может подменить провайдер своим")
    void serviceCanOverrideLockProvider() {
        LockProvider custom = mock(LockProvider.class);

        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean("customLockProvider", LockProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(LockProvider.class);
                    assertThat(context.getBean(LockProvider.class)).isSameAs(custom);
                });
    }

    @Test
    @DisplayName("Таблица блокировок квалифицируется схемой сервиса")
    void lockTableIsTakenFromProperties() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("petstore.scheduler.table-name=inventory.shedlock")
                .run(context -> assertThat(context.getBean(CommonCoreProperties.class)
                        .getScheduler().getTableName()).isEqualTo("inventory.shedlock"));
    }

    @Configuration
    @EnableConfigurationProperties(CommonCoreProperties.class)
    static class PropertiesConfig {
    }
}
