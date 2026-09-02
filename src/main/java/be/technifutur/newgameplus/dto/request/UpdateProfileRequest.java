package be.technifutur.newgameplus.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(

        @NotBlank
        String username,

        @NotBlank
        @Email
        String email
) {
}
