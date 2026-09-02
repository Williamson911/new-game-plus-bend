package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.CartItem;

import java.math.BigDecimal;
import java.util.UUID;

public record CartItemResponse(
        UUID listingId,
        String gameName,
        String shopName,
        BigDecimal price
) {
    public static CartItemResponse fromCartItem(CartItem item) {
        return new CartItemResponse(
                item.getListing().getId(),
                item.getListing().getGame().getName(),
                item.getListing().getShop().getName(),
                item.getListing().getPrice()
        );
    }
}
