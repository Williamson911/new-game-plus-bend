package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID listingId,
        String gameName,
        BigDecimal price
) {
    public static OrderItemResponse fromOrderItem(OrderItem item) {
        return new OrderItemResponse(
                item.getListing().getId(),
                item.getListing().getGame().getName(),
                item.getPrice()
        );
    }
}
