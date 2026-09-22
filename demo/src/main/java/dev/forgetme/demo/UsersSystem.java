package dev.forgetme.demo;

import dev.forgetme.connector.ErasureHandler;
import dev.forgetme.connector.ErasureResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stage 3: the customer accounts. Deleted last, because every other system needs them to find the customer. */
@RestController
@Profile("users")
class UsersSystem implements ErasureHandler {

    private final Map<String, String> accounts = new ConcurrentHashMap<>(Map.of(
            "alice@example.com", "Alice Smith, customer since 2021",
            "bob@example.com", "Bob Jones, customer since 2023"));

    @GetMapping("/data")
    Map<String, String> data() {
        return accounts;
    }

    @Override
    public ErasureResult erase(Subject subject) {
        return accounts.remove(subject.email()) == null
                ? ErasureResult.deleted("no account found, nothing to delete")
                : ErasureResult.deleted();
    }
}
