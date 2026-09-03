package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Address;
import be.technifutur.newgameplus.entities.DeliveryMode;
import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import be.technifutur.newgameplus.entities.OrderStatus;
import be.technifutur.newgameplus.entities.RelayPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String shopName,
        OrderStatus status,
        DeliveryMode deliveryMode,
        Address shippingAddress,
        RelayPoint relayPoint,
        BigDecimal shippingCost,
        LocalDateTime createdAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse fromOrder(Order order, List<OrderItem> items) {
        return new OrderResponse(
                order.getId(),
                order.getShop().getName(),
                order.getStatus(),
                order.getDeliveryMode(),
                order.getShippingAddress(),
                order.getRelayPoint(),
                order.getShippingCost(),
                order.getCreatedAt(),
                items.stream().map(OrderItemResponse::fromOrderItem).toList()
        );
    }
}
