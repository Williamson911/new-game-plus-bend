package be.technifutur.newgameplus.dto.response;

import java.util.List;

public record CheckoutResponse(
        List<OrderResponse> orders,
        String checkoutUrl
) {
}
