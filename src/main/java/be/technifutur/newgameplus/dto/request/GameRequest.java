package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Game;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameRequest(
        @NotBlank String name,
        @NotBlank String description,
        @NotEmpty List<UUID> genreIds,
        @NotBlank String publisher,
        @NotBlank String developer,
        @NotBlank String platform,
        @NotNull LocalDate releaseDate,
        String coverURL,
        String igdbID
) {
    public Game toGame() {
        Game game = new Game();
        game.setName(name);
        game.setDescription(description);
        game.setPublisher(publisher);
        game.setDeveloper(developer);
        game.setPlatform(platform);
        game.setReleaseDate(releaseDate);
        game.setCoverURL(coverURL);
        game.setIgdbID(igdbID);
        return game;
    }
}
