package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderStatus;
import be.technifutur.newgameplus.repositories.OrderRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Webhook Stripe")
public class PaymentController {

    private final OrderRepository orderRepository;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @PostConstruct
    public void validateWebhookSecret() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("stripe.webhook-secret must be configured (set STRIPE_WEBHOOK_SECRET) for the payment webhook to function");
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            HttpServletRequest request,
            @RequestHeader("Stripe-Signature") String signature
    ) throws IOException {
        String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature invalide: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if ("checkout.session.completed".equals(event.getType())) {
            event.getDataObjectDeserializer().getObject()
                    .filter(Session.class::isInstance)
                    .map(Session.class::cast)
                    .ifPresent(this::markOrdersPaid);
        }

        return ResponseEntity.ok().build();
    }

    private void markOrdersPaid(Session session) {
        List<Order> orders = orderRepository.findByStripeSessionId(session.getId());
        if (orders.isEmpty()) {
            log.warn("Webhook Stripe reçu pour une session inconnue: {}", session.getId());
            return;
        }

        orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.PENDING)
                .forEach(order -> {
                    order.setStatus(OrderStatus.PAID);
                    orderRepository.save(order);
                });
    }
}
