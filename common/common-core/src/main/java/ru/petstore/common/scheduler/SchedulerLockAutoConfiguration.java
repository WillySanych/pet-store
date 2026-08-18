package ru.petstore.common.scheduler;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.petstore.common.autoconfigure.CommonCoreProperties;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({LockProvider.class, JdbcTemplateLockProvider.class})
@ConditionalOnBean(DataSource.class)
@EnableSchedulerLock(defaultLockAtMostFor = "${petstore.scheduler.default-lock-at-most-for:PT10M}")
public class SchedulerLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(DataSource dataSource, CommonCoreProperties properties) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(properties.getScheduler().getTableName())
                .usingDbTime()
                .build());
    }
}
