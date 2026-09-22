package dev.forgetme.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One app, four systems: the Spring profile picks which one this copy plays
 * (users, orders, uploads or mailing). Docker Compose runs four copies.
 * Every system shows what it currently stores at GET /data, so you can watch data disappear.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
