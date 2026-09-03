package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Address;
import be.technifutur.newgameplus.entities.DeliveryMode;
import be.technifutur.newgameplus.entities.RelayPoint;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull DeliveryMode deliveryMode,
        String street,
        String streetNumber,
        String postCode,
        String city,
        String country,
        String relayPointId,
        String relayPointName,
        String relayPointStreet,
        String relayPointPostCode,
        String relayPointCity,
        String relayPointCountry
) {

    public Address toAddress() {
        return new Address(city, street, streetNumber, postCode, country);
    }

    public RelayPoint toRelayPoint() {
        return new RelayPoint(relayPointId, relayPointName, relayPointStreet, relayPointPostCode, relayPointCity, relayPointCountry);
    }
}
