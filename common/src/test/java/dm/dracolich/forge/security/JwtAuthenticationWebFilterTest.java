package dm.dracolich.forge.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationWebFilterTest {

    private JwtAuthenticationWebFilter filter;
    private ECPrivateKey privateKey;
    private ECPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (ECPrivateKey) keyPair.getPrivate();
        publicKey = (ECPublicKey) keyPair.getPublic();

        filter = new JwtAuthenticationWebFilter(filterValidator());
    }

    private JwtTokenValidator filterValidator() {
        return token -> Mono.fromCallable(() ->
                Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
        );
    }

    private String generateToken(String subject, String accessLevel) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(privateKey, Jwts.SIG.ES384);
        if (accessLevel != null) {
            builder.claim("accessLevel", accessLevel);
        }
        return builder.compact();
    }

    private ServerWebExchange mockExchange(String authHeaderValue) {
        HttpHeaders headers = new HttpHeaders();
        if (authHeaderValue != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeaderValue);
        }
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        return exchange;
    }

    @Test
    void validToken_setsSecurityContext() {
        String token = generateToken("user-123", "ADMIN");
        ServerWebExchange exchange = mockExchange("Bearer " + token);

        AtomicReference<SecurityContext> capturedContext = new AtomicReference<>();

        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(capturedContext::set)
                .then();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(capturedContext.get());
        Principal principal = (Principal) capturedContext.get().getAuthentication().getPrincipal();
        assertEquals(PrincipalType.USER, principal.type());
        assertEquals("user-123", principal.id());
        assertTrue(capturedContext.get().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void noAuthHeader_passesThrough() {
        ServerWebExchange exchange = mockExchange(null);

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertTrue(chainCalled.get());
    }

    @Test
    void nonBearerHeader_passesThrough() {
        ServerWebExchange exchange = mockExchange("Basic dXNlcjpwYXNz");

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertTrue(chainCalled.get());
    }

    private static MockServerWebExchange realExchange(String authHeaderValue) {
        var request = MockServerHttpRequest.post("/decks/");
        if (authHeaderValue != null) {
            request.header(HttpHeaders.AUTHORIZATION, authHeaderValue);
        }
        return MockServerWebExchange.from(request);
    }

    private static void assertRejectedAsInvalidToken(MockServerWebExchange exchange, AtomicInteger chainCalls) {
        assertEquals(0, chainCalls.get(), "a request with a bad token must not reach the handler");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("Bearer error=\"invalid_token\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains("\"success\":false"), body);
        assertTrue(body.contains("\"httpStatus\":\"UNAUTHORIZED\""), body);
    }

    private static WebFilterChain countingChain(AtomicInteger calls) {
        return ex -> {
            calls.incrementAndGet();
            return Mono.empty();
        };
    }

    @Test
    void invalidToken_isRejectedWith401() {
        MockServerWebExchange exchange = realExchange("Bearer invalid.jwt.token");
        AtomicInteger chainCalls = new AtomicInteger();

        StepVerifier.create(filter.filter(exchange, countingChain(chainCalls)))
                .verifyComplete();

        assertRejectedAsInvalidToken(exchange, chainCalls);
    }

    @Test
    void expiredToken_isRejectedWith401() {
        Instant past = Instant.now().minusSeconds(3600);
        String expiredToken = Jwts.builder()
                .subject("user-123")
                .claim("accessLevel", "COMMON_USER")
                .issuedAt(Date.from(past.minusSeconds(900)))
                .expiration(Date.from(past))
                .signWith(privateKey, Jwts.SIG.ES384)
                .compact();
        MockServerWebExchange exchange = realExchange("Bearer " + expiredToken);
        AtomicInteger chainCalls = new AtomicInteger();

        StepVerifier.create(filter.filter(exchange, countingChain(chainCalls)))
                .verifyComplete();

        assertRejectedAsInvalidToken(exchange, chainCalls);
    }

    @Test
    void validatorReturningNothing_isRejectedWith401() {
        JwtAuthenticationWebFilter emptyValidatorFilter = new JwtAuthenticationWebFilter(token -> Mono.empty());
        MockServerWebExchange exchange = realExchange("Bearer some.token.here");
        AtomicInteger chainCalls = new AtomicInteger();

        StepVerifier.create(emptyValidatorFilter.filter(exchange, countingChain(chainCalls)))
                .verifyComplete();

        assertRejectedAsInvalidToken(exchange, chainCalls);
    }

    @Test
    void tokenWithoutSubject_isRejectedWith401() {
        Instant now = Instant.now();
        String noSubject = Jwts.builder()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(privateKey, Jwts.SIG.ES384)
                .compact();
        MockServerWebExchange exchange = realExchange("Bearer " + noSubject);
        AtomicInteger chainCalls = new AtomicInteger();

        StepVerifier.create(filter.filter(exchange, countingChain(chainCalls)))
                .verifyComplete();

        assertRejectedAsInvalidToken(exchange, chainCalls);
    }

    @Test
    void downstreamError_reachesTheCallerOnce_andTheHandlerRunsOnce() {
        String token = generateToken("user-123", "COMMON_USER");
        MockServerWebExchange exchange = realExchange("Bearer " + token);
        AtomicInteger chainCalls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("handler failed after writing");

        WebFilterChain failingChain = ex -> {
            chainCalls.incrementAndGet();
            return Mono.error(failure);
        };

        StepVerifier.create(filter.filter(exchange, failingChain))
                .expectErrorMatches(e -> e == failure)
                .verify();

        assertEquals(1, chainCalls.get(), "the handler must run exactly once");
        assertNull(exchange.getResponse().getStatusCode(), "a handler error is not an authentication failure");
    }

    @Test
    void badTokenOnAnIgnoredRequest_passesThroughWithoutAPrincipal() {
        JwtAuthenticationWebFilter ignoringFilter = new JwtAuthenticationWebFilter(
                filterValidator(),
                ex -> ex.getRequest().getPath().value().startsWith("/auth/"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"));
        AtomicInteger chainCalls = new AtomicInteger();
        AtomicReference<Boolean> hadContext = new AtomicReference<>();

        WebFilterChain chain = ex -> {
            chainCalls.incrementAndGet();
            return ReactiveSecurityContextHolder.getContext()
                    .hasElement()
                    .doOnNext(hadContext::set)
                    .then();
        };

        StepVerifier.create(ignoringFilter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(1, chainCalls.get());
        assertFalse(hadContext.get(), "an ignored token must not authenticate the request");
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void badTokenOnARequestThatIsNotIgnored_isStillRejected() {
        JwtAuthenticationWebFilter ignoringFilter = new JwtAuthenticationWebFilter(
                filterValidator(),
                ex -> ex.getRequest().getPath().value().startsWith("/auth/"));
        MockServerWebExchange exchange = realExchange("Bearer invalid.jwt.token");
        AtomicInteger chainCalls = new AtomicInteger();

        StepVerifier.create(ignoringFilter.filter(exchange, countingChain(chainCalls)))
                .verifyComplete();

        assertRejectedAsInvalidToken(exchange, chainCalls);
    }

    @Test
    void tokenWithoutAccessLevel_setsEmptyAuthorities() {
        String token = generateToken("user-456", null);
        ServerWebExchange exchange = mockExchange("Bearer " + token);

        AtomicReference<SecurityContext> capturedContext = new AtomicReference<>();

        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(capturedContext::set)
                .then();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(capturedContext.get());
        Principal principal = (Principal) capturedContext.get().getAuthentication().getPrincipal();
        assertEquals(PrincipalType.USER, principal.type());
        assertEquals("user-456", principal.id());
        assertTrue(capturedContext.get().getAuthentication().getAuthorities().isEmpty());
    }
}
