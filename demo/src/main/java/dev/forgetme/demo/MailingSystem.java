package dev.forgetme.demo;

import dev.forgetme.connector.ErasureHandler;
import dev.forgetme.connector.ErasureResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stage 1: the newsletter list. Goes first so nobody gets marketing email while the rest is being deleted. */
@RestController
@Profile("mailing")
class MailingSystem implements ErasureHandler {

    private final Set<String> subscribers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> seen = ConcurrentHashMap.newKeySet();

    MailingSystem() {
        subscribers.addAll(List.of("alice@example.com", "bob@example.com", "carol@example.com"));
    }

    @GetMapping("/data")
    Set<String> data() {
        return subscribers;
    }

    /** Deliberately flaky: fails the first try of every request, so you can watch ForgetMe retry. */
    @Override
    public ErasureResult erase(Subject subject) {
        if (seen.add(subject.requestId())) {
            throw new IllegalStateException("mail provider timed out (demo: the first try always fails)");
        }
        subscribers.remove(subject.email());
        return ErasureResult.deleted("unsubscribed and removed from the list");
    }
}
