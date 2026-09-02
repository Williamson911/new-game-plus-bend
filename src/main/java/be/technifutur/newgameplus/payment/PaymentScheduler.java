package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import be.technifutur.newgameplus.entities.OrderStatus;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.OrderItemRepository;
import be.technifutur.newgameplus.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;

    @Value("${payment.pending-expiration-minutes}")
    private long expirationMinutes;

    @Scheduled(fixedRateString = "${payment.expiration-check-rate-ms}")
    @Transactional
    public void cancelStalePendingOrders() {
        long effectiveExpirationMinutes = Math.max(30, Math.min(expirationMinutes, 1440));
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(effectiveExpirationMinutes);
        List<Order> staleOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);

        for (Order order : staleOrders) {
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            for (OrderItem item : items) {
                Listing listing = item.getListing();
                listing.setStatus(ListingStatus.AVAILABLE);
                listingRepository.save(listing);
            }
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            log.info("Commande {} annulée automatiquement (PENDING depuis plus de {} min)", order.getId(), effectiveExpirationMinutes);
        }
    }
}
