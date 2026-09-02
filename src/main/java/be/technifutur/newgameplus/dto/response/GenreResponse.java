package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Genre;

import java.util.UUID;

public record GenreResponse(UUID id, String name) {

    public static GenreResponse fromGenre(Genre genre) {
        return new GenreResponse(genre.getId(), genre.getName());
    }
}
