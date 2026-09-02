package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Address;
import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(
        @NotBlank String street,
        @NotBlank String streetNumber,
        @NotBlank String postCode,
        @NotBlank String city,
        @NotBlank String country
) {

    public Address toAddress() {
        return new Address(city, street, streetNumber, postCode, country);
    }
}