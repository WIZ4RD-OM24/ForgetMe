package dev.forgetme;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops someone guessing the admin password. Ten wrong logins from one address and that address is turned away for
 * the rest of the hour. Only wrong logins count, so an admin clicking around is never locked out.
 *
 * <p>Order -200 puts this ahead of Spring Security (-100), so a blocked address never reaches the password check.
 */
@Component
@Order(-200)
class AdminLoginGuard extends OncePerRequestFilter {

    static final int MAX_FAILURES_PER_HOUR = 10;

    private final RateLimiter failures;

    AdminLoginGuard(RateLimiter failures) {
        this.failures = failures;
    }

    @EventListener
    void wrongPassword(AbstractAuthenticationFailureEvent event) {
        if (event.getAuthentication().getDetails() instanceof WebAuthenticationDetails where) {
            failures.record(key(where.getRemoteAddress()), Instant.now());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (failures.used(key(request.getRemoteAddr()), Instant.now()) >= MAX_FAILURES_PER_HOUR) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many failed logins. Try again later.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String key(String address) {
        return "login:" + address;
    }
}
