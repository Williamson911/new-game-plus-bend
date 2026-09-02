package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Genre;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record GenreRequest(
        @NotBlank String name) {
    public Genre toGenre() {
        Genre genre = new Genre();
        genre.setName(name);
        return genre;
    }
}