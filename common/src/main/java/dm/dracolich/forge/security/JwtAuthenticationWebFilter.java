package dm.dracolich.forge.security;

import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Turns a valid {@code Authorization: Bearer} token into a {@link Principal} of type USER.
 *
 * <ul>
 *   <li>No {@code Authorization} header, or a scheme other than Bearer: the request passes through
 *       with no principal, so the anonymous cookie filter can identify it.</li>
 *   <li>A Bearer token that fails validation (expired, badly signed, malformed, or with no subject):
 *       the request is answered with 401 and {@code WWW-Authenticate: Bearer error="invalid_token"}
 *       (RFC 6750 section 3.1), so the client refreshes its token and retries. It is never downgraded
 *       to an anonymous caller.</li>
 *   <li>Requests matched by {@code tokenIgnoredFor} are treated as if they had no header. Use it for
 *       endpoints that must work with a stale token, such as login and token refresh.</li>
 * </ul>
 *
 * Only the token's validation is guarded: an error raised by the rest of the chain reaches the caller
 * unchanged, and the chain runs once.
 */
public class JwtAuthenticationWebFilter implements WebFilter, Ordered {

    public static final int ORDER = 10;

    static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private static final byte[] INVALID_TOKEN_BODY = ("{\"success\":false,\"httpStatus\":\"UNAUTHORIZED\","
            + "\"message\":\"The access token is invalid or has expired.\",\"payload\":null,\"errors\":null}")
            .getBytes(StandardCharsets.UTF_8);

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationWebFilter.class);

    private final JwtTokenValidator tokenValidator;
    private final Predicate<ServerWebExchange> tokenIgnoredFor;

    public JwtAuthenticationWebFilter(JwtTokenValidator tokenValidator) {
        this(tokenValidator, exchange -> false);
    }

    /**
     * @param tokenIgnoredFor requests for which the bearer token is ignored rather than validated
     */
    public JwtAuthenticationWebFilter(JwtTokenValidator tokenValidator,
                                      Predicate<ServerWebExchange> tokenIgnoredFor) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.tokenIgnoredFor = Objects.requireNonNull(tokenIgnoredFor, "tokenIgnoredFor");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ") || tokenIgnoredFor.test(exchange)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7);
        return authenticate(token, exchange)
                .flatMap(authentication -> authentication
                        .map(auth -> chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                        .orElseGet(() -> rejectInvalidToken(exchange.getResponse())));
    }

    /**
     * Validates the token. Always emits exactly one value: the authentication, or empty when the token
     * is not acceptable. Errors stop here, so they can't be confused with errors from the chain.
     */
    private Mono<Optional<Authentication>> authenticate(String token, ServerWebExchange exchange) {
        return Mono.defer(() -> tokenValidator.validate(token))
                .map(JwtAuthenticationWebFilter::toAuthentication)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("JWT authentication failed for {}: {}", exchange.getRequest().getPath(), e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    private static Authentication toAuthentication(Claims claims) {
        String accessLevel = claims.get("accessLevel", String.class);
        List<SimpleGrantedAuthority> authorities = accessLevel != null
                ? List.of(new SimpleGrantedAuthority("ROLE_" + accessLevel))
                : List.of();
        // Principal.user rejects a null subject, which makes a token without one invalid.
        Principal principal = Principal.user(claims.getSubject());
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private static Mono<Void> rejectInvalidToken(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer body = response.bufferFactory().wrap(INVALID_TOKEN_BODY);
        return response.writeWith(Mono.just(body));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
