package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.CartItem;

import java.util.List;

public record CartResponse(
        List<CartItemResponse> items
) {
    public static CartResponse fromCart(List<CartItem> items) {
        return new CartResponse(
                items.stream().map(CartItemResponse::fromCartItem).toList()
        );
    }
}
