package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(
        @NotNull OrderStatus status
) {
}
