package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.GenreRequest;
import be.technifutur.newgameplus.dto.response.GenreResponse;
import be.technifutur.newgameplus.entities.Genre;
import be.technifutur.newgameplus.repositories.GameRepository;
import be.technifutur.newgameplus.repositories.GenreRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/genres")
@RequiredArgsConstructor
@Tag(name = "Genres", description = "Genres de jeux (lecture publique, gestion réservée aux admins)")
public class GenreController {

    private final GenreRepository genreRepository;
    private final GameRepository gameRepository;

    @GetMapping
    public ResponseEntity<List<GenreResponse>> findAll() {
        List<GenreResponse> genres = genreRepository.findAll().stream()
                .map(GenreResponse::fromGenre)
                .toList();
        return ResponseEntity.ok(genres);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GenreResponse> findById(@PathVariable UUID id) {
        Genre genre = genreRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Genre introuvable"));
        return ResponseEntity.ok(GenreResponse.fromGenre(genre));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<GenreResponse> create(@Valid @RequestBody GenreRequest request) {
        if (genreRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce genre existe déjà");
        }

        Genre genre = genreRepository.save(request.toGenre());
        return ResponseEntity.status(HttpStatus.CREATED).body(GenreResponse.fromGenre(genre));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<GenreResponse> update(@PathVariable UUID id, @Valid @RequestBody GenreRequest request) {
        Genre genre = genreRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Genre introuvable"));

        if (genreRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce genre existe déjà");
        }

        genre.setName(request.name());
        genreRepository.save(genre);

        return ResponseEntity.ok(GenreResponse.fromGenre(genre));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!genreRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Genre introuvable");
        }
        if (gameRepository.existsByGenresId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce genre est encore utilisé par au moins un jeu");
        }

        genreRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
