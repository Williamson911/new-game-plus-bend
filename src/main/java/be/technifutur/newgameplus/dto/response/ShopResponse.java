package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Shop;

import java.util.UUID;

public record ShopResponse(
        UUID id,
        String name,
        String description
) {
    public static ShopResponse fromShop(Shop shop) {
        return new ShopResponse(shop.getId(), shop.getName(), shop.getDescription());
    }
}
