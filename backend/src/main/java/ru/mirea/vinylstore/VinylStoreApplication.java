package ru.mirea.vinylstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Точка входа серверного приложения SPIN Vinyl Store.
 *
 * <p>Bootstrap-класс содержит только запуск Spring Boot. Бизнес-логика, безопасность
 * и инфраструктурный код вынесены в отдельные компоненты.</p>
 */
@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class VinylStoreApplication {

    /**
     * Запускает Spring Boot и создаёт контекст приложения.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        SpringApplication.run(VinylStoreApplication.class, args);
    }
}
