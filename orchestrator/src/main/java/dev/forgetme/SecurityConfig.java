package dev.forgetme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    /**
     * Filing, verifying and cancelling are public (the request ID is an unguessable UUID). Callbacks are public too,
     * but every one must carry a valid connector signature. Everything else is admin-only.
     */
    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // stateless JSON API: no cookies for CSRF to abuse
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/requests", "/api/requests/*/verify", "/api/requests/*/cancel",
                                "/api/callbacks/*").permitAll()
                        .requestMatchers("/error").permitAll() // or every 4xx on a public endpoint turns into 401
                        .anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
