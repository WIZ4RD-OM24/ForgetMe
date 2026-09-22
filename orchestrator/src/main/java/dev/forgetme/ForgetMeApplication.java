package dev.forgetme;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ForgetMeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgetMeApplication.class, args);
    }
}
