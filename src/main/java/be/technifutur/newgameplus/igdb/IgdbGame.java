package be.technifutur.newgameplus.igdb;

import java.time.LocalDate;

public record IgdbGame(
        String igdbId,
        String description,
        String coverURL,
        LocalDate releaseDate
) {
}
