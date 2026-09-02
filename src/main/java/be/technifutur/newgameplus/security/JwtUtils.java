package be.technifutur.newgameplus.security;

import be.technifutur.newgameplus.entities.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtUtils {

    private final JwtBuilder builder;

    private final JwtParser parser;

    private static final long TOKEN_LIFETIME_MS = 24L * 60 * 60 * 1000; // 24h

    public JwtUtils() {
        var secret = "changeme-changeme-changeme-changeme-changeme-changeme".getBytes(StandardCharsets.UTF_8);
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");

        this.builder = Jwts.builder().signWith(key);
        this.parser = Jwts.parser().verifyWith(key).build();
    }

    public String generateToken(User u) {
        return builder
                .subject(u.getUsername())
                .claim("id", u.getId().toString())
                .claim("email", u.getEmail())
                .claim("roles", u.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_LIFETIME_MS))
                .compact();
    }

    public Claims getClaims(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public UUID getId(String token) {
        return UUID.fromString(getClaims(token).get("id", String.class));
    }

    public List<String> getRoles(String token) {
        List<?> roles = getClaims(token).get("roles", List.class);
        return roles != null ? roles.stream().map(Object::toString).toList() : List.of();
    }

    public Collection<? extends GrantedAuthority> getAuthorities(String token) {
        return getRoles(token).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    public String getUsername(String token) {
        return getClaims(token).getSubject();
    }

    public UserSession getUser(String token) {
        return new UserSession(getId(token), getUsername(token));
    }

    public boolean isValid(String token) {
        Claims claims = getClaims(token);
        Date now = new Date();
        return now.after(claims.getIssuedAt()) && now.before(claims.getExpiration());
    }

    public record UserSession(
            UUID id,
            String username
    ) {
    }
}
