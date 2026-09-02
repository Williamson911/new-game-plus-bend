package be.technifutur.newgameplus.dto.response;

import java.util.UUID;

public record RegisterResponse(
        UUID id,
        String username
) {
}