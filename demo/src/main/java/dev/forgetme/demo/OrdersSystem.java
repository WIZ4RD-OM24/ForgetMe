package dev.forgetme.demo;

import dev.forgetme.connector.ErasureHandler;
import dev.forgetme.connector.ErasureResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stage 2: past orders. The law says invoices must be kept, so orders stay but lose the email. */
@RestController
@Profile("orders")
class OrdersSystem implements ErasureHandler {

    record Order(int id, String email, String item, String total) {}

    private final List<Order> orders = new CopyOnWriteArrayList<>(List.of(
            new Order(1001, "alice@example.com", "Desk lamp", "$40"),
            new Order(1002, "bob@example.com", "Keyboard", "$85"),
            new Order(1003, "alice@example.com", "Headphones", "$120")));

    @GetMapping("/data")
    List<Order> data() {
        return orders;
    }

    @Override
    public ErasureResult erase(Subject subject) {
        orders.replaceAll(o -> subject.email().equals(o.email()) ? new Order(o.id(), null, o.item(), o.total()) : o);
        return ErasureResult.retained("invoices kept 8 years for tax law; email removed from them");
    }
}
