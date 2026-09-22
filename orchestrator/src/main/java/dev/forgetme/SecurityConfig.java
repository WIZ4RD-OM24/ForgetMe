package dev.forgetme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    /**
     * The admin web page. Separate from the API chain because browser forms need CSRF protection and a session:
     * without it, another site could make a logged-in admin's browser press "retry" for them.
     */
    @Bean
    @Order(1)
    SecurityFilterChain adminPages(HttpSecurity http) throws Exception {
        return http.securityMatcher("/admin/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * Filing, verifying and cancelling are public (the request ID is an unguessable UUID). Callbacks are public too,
     * but every one must carry a valid connector signature. Everything else is admin-only.
     */
    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // stateless JSON API: no cookies for CSRF to abuse
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/requests", "/api/requests/*/verify", "/api/requests/*/cancel",
                                "/api/callbacks/*", "/", "/r/*/confirm", "/r/*/cancel").permitAll()
                        .requestMatchers("/", "/r/*").permitAll() // the pages for the person asking to be deleted
                        .requestMatchers("/error", "/style.css").permitAll() // or every 4xx on a public endpoint turns into 401
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll() // API docs are public
                        .anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
