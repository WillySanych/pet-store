package ru.petstore.common.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import ru.petstore.common.web.GlobalExceptionHandler;

@SpringBootTest(classes = ImportedApplication.class)
class ImportedConfigCollisionTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Обработчик ошибок не дублируется даже при явном @Import автоконфигурации")
    void exceptionHandlerIsNotDuplicatedWhenAutoConfigurationIsImported() {
        assertThat(applicationContext.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
    }
}
