package dev.forgetme.connector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Switches on automatically in any app that defines an ErasureHandler bean. */
@AutoConfiguration
@ConditionalOnBean(ErasureHandler.class)
public class ForgetMeConnectorAutoConfiguration {

    /** forgetme.connector.secret is the secret ForgetMe gave when this system was registered. */
    @Bean
    ErasureEndpoint forgetMeErasureEndpoint(ErasureHandler handler, ObjectMapper json,
                                            @Value("${forgetme.connector.secret}") String secret) {
        return new ErasureEndpoint(handler, secret, json);
    }
}
