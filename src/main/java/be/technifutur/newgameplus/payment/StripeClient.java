package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Component
public class StripeClient {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    @Value("${payment.pending-expiration-minutes}")
    private long pendingExpirationMinutes;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Throws a checked {@link StripeException}. Callers inside a {@code @Transactional}
     * method must translate this to an unchecked exception (or use {@code rollbackFor})
     * — otherwise a failed Stripe call will not roll back the transaction.
     */
    public CheckoutSession createCheckoutSession(List<OrderItem> items, List<Order> orders) throws StripeException {
        long expiresInMinutes = Math.max(30, Math.min(pendingExpirationMinutes, 1440));
        long expiresAt = Instant.now().plusSeconds(expiresInMinutes * 60).getEpochSecond();

        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setExpiresAt(expiresAt);

        for (OrderItem item : items) {
            long unitAmount = item.getPrice()
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();

            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    SessionCreateParams.LineItem.PriceData.builder()
                                            .setCurrency("eur")
                                            .setUnitAmount(unitAmount)
                                            .setProductData(
                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                            .setName(item.getListing().getGame().getName())
                                                            .build()
                                            )
                                            .build()
                            )
                            .build()
            );
        }

        for (Order order : orders) {
            BigDecimal shippingCost = order.getShippingCost();
            long shippingUnitAmount = shippingCost
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();

            String shippingLabel = orders.size() == 1
                    ? "Livraison"
                    : "Livraison - " + order.getShop().getName();

            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    SessionCreateParams.LineItem.PriceData.builder()
                                            .setCurrency("eur")
                                            .setUnitAmount(shippingUnitAmount)
                                            .setProductData(
                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                            .setName(shippingLabel)
                                                            .build()
                                            )
                                            .build()
                            )
                            .build()
            );
        }

        Session session = Session.create(paramsBuilder.build());
        return new CheckoutSession(session.getId(), session.getUrl());
    }

    public record CheckoutSession(String id, String url) {
    }
}
