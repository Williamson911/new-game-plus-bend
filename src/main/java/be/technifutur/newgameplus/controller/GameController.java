package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.GameRequest;
import be.technifutur.newgameplus.dto.response.GameResponse;
import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Genre;
import be.technifutur.newgameplus.repositories.GameRepository;
import be.technifutur.newgameplus.repositories.GenreRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/games")
@RequiredArgsConstructor
@Tag(name = "Games", description = "Catalogue des jeux")
public class GameController {

    private final GameRepository gameRepository;
    private final GenreRepository genreRepository;

    @GetMapping
    public ResponseEntity<Page<GameResponse>> findAll(
            @RequestParam(required = false) String name,
            Pageable pageable
    ) {
        Page<Game> games = (name == null || name.isBlank())
                ? gameRepository.findAll(pageable)
                : gameRepository.findByNameContainingIgnoreCase(name, pageable);

        return ResponseEntity.ok(games.map(GameResponse::fromGame));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GameResponse> findById(@PathVariable UUID id) {
        Game game = gameRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game introuvable"));
        return ResponseEntity.ok(GameResponse.fromGame(game));
    }

    @PostMapping
    public ResponseEntity<GameResponse> create(@Valid @RequestBody GameRequest request) {
        if (gameRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un jeu porte déjà ce nom");
        }

        Game game = request.toGame();
        Set<Genre> genres = new HashSet<>(genreRepository.findAllById(request.genreIds()));
        game.setGenres(genres);

        gameRepository.save(game);
        return ResponseEntity.status(HttpStatus.CREATED).body(GameResponse.fromGame(game));
    }
}
