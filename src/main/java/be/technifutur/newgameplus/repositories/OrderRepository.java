package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByBuyerId(UUID buyerId);

    List<Order> findByShopId(UUID shopId);

    List<Order> findByStripeSessionId(String stripeSessionId);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
