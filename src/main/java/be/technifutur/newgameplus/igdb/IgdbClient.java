package be.technifutur.newgameplus.igdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class IgdbClient {

    private final RestClient authClient = RestClient.create("https://id.twitch.tv");
    private final RestClient igdbClient = RestClient.create("https://api.igdb.com");

    @Value("${igdb.client-id}")
    private String clientId;

    @Value("${igdb.client-secret}")
    private String clientSecret;

    private String accessToken;

    public Optional<IgdbGame> searchGame(String query) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            log.warn("IGDB client-id/client-secret not configured, skipping IGDB lookup for '{}'", query);
            return Optional.empty();
        }

        try {
            String body = """
                    search "%s";
                    fields name,summary,first_release_date,cover.url;
                    limit 1;
                    """.formatted(query.replace("\"", ""));

            JsonNode response = igdbClient.post()
                    .uri("/v4/games")
                    .header("Client-ID", clientId)
                    .header("Authorization", "Bearer " + getAccessToken())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.isArray() || response.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(toIgdbGame(response.get(0)));
        } catch (RuntimeException e) {
            log.error("IGDB lookup failed for '{}': {}", query, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private IgdbGame toIgdbGame(JsonNode game) {
        String igdbId = game.get("id").asText();
        String description = game.hasNonNull("summary") ? game.get("summary").asText() : null;

        String coverURL = null;
        if (game.hasNonNull("cover") && game.get("cover").hasNonNull("url")) {
            coverURL = "https:" + game.get("cover").get("url").asText().replace("t_thumb", "t_cover_big");
        }

        LocalDate releaseDate = null;
        if (game.hasNonNull("first_release_date")) {
            releaseDate = Instant.ofEpochSecond(game.get("first_release_date").asLong())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
        }

        return new IgdbGame(igdbId, description, coverURL, releaseDate);
    }

    @SuppressWarnings("unchecked")
    private String getAccessToken() {
        if (accessToken != null) {
            return accessToken;
        }

        Map<String, Object> response = authClient.post()
                .uri(uriBuilder -> uriBuilder.path("/oauth2/token")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("grant_type", "client_credentials")
                        .build())
                .retrieve()
                .body(Map.class);

        accessToken = (String) response.get("access_token");
        return accessToken;
    }
}
