package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.User;

import java.time.LocalDateTime;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String username,
        String email,
        LocalDateTime createdAt
) {

    public static MeResponse fromUser(User user) {
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getCreatedAt()
        );
    }
}
