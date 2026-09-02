package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Shop;
import jakarta.validation.constraints.NotBlank;

public record ShopRequest(
        @NotBlank String name,
        String description
) {
    public Shop toShop() {
        Shop shop = new Shop();
        shop.setName(name);
        shop.setDescription(description);
        return shop;
    }
}
