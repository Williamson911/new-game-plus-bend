package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Genre;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameResponse(
        UUID id,
        String name,
        String description,
        List<String> genres,
        String publisher,
        String developer,
        String platform,
        LocalDate releaseDate,
        String coverURL,
        int weightGrams
) {
    public static GameResponse fromGame(Game game) {
        return new GameResponse(
                game.getId(),
                game.getName(),
                game.getDescription(),
                game.getGenres().stream().map(Genre::getName).toList(),
                game.getPublisher(),
                game.getDeveloper(),
                game.getPlatform(),
                game.getReleaseDate(),
                game.getCoverURL(),
                game.getWeightGrams()
        );
    }
}
