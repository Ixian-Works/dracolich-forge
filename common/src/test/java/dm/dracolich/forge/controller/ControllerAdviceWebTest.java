package dm.dracolich.forge.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests that go wrong before a service sees them, sent through the real WebFlux dispatch to a
 * small controller behind {@link ControllerAdvice}. {@link ControllerAdviceTest} calls the handler
 * methods directly; this shows which handler Spring picks for each failure, and what is on the wire.
 */
class ControllerAdviceWebTest {

    enum Format { COMMANDER, MODERN }

    static class DeckRequest {
        private String name;
        private Integer size;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getSize() { return size; }
        public void setSize(Integer size) { this.size = size; }
    }

    @RestController
    static class DeckController {

        @GetMapping("/decks")
        Mono<String> list(@RequestParam("format") Format format,
                          @RequestParam(name = "page", defaultValue = "0") int page) {
            return Mono.just(format + " page " + page);
        }

        @PostMapping(path = "/decks", consumes = MediaType.APPLICATION_JSON_VALUE)
        Mono<String> create(@Validated @RequestBody DeckRequest request) {
            return Mono.just(request.getName());
        }

        @GetMapping("/broken")
        Mono<String> broken() {
            throw new IllegalStateException("the database password is hunter2");
        }
    }

    /** Stands in for Bean Validation, which forge does not depend on: a name, and a size above 0. */
    static class DeckRequestValidator implements Validator {
        @Override
        public boolean supports(Class<?> type) {
            return DeckRequest.class.isAssignableFrom(type);
        }

        @Override
        public void validate(Object target, Errors errors) {
            DeckRequest request = (DeckRequest) target;
            if (request.getName() == null || request.getName().isBlank()) {
                errors.rejectValue("name", "NotBlank", "must not be blank");
            }
            if (request.getSize() != null && request.getSize() < 1) {
                errors.rejectValue("size", "Min", "must be greater than 0");
            }
        }
    }

    private final WebTestClient client = WebTestClient
            .bindToController(new DeckController())
            .controllerAdvice(new ControllerAdvice())
            .validator(new DeckRequestValidator())
            .build();

    @Test
    void aValueOutsideAnEnumIs400AndNamesTheParameter() {
        JsonNode body = failure(client.get().uri("/decks?format=NOPE"), HttpStatus.BAD_REQUEST);

        assertEquals(List.of("DMD043 format"), codesAndFields(body));
    }

    @Test
    void aNumberThatIsNotOneIs400AndNamesTheParameter() {
        JsonNode body = failure(
                client.get().uri("/decks?format=MODERN&page=two"), HttpStatus.BAD_REQUEST);

        assertEquals(List.of("DMD043 page"), codesAndFields(body));
    }

    @Test
    void aMissingRequiredParameterIs400AndNamesIt() {
        JsonNode body = failure(client.get().uri("/decks"), HttpStatus.BAD_REQUEST);

        assertEquals(List.of("DMD043 format"), codesAndFields(body));
        assertTrue(message(body).contains("format"), message(body));
    }

    @Test
    void aBodyThatFailsValidationIs400WithOneErrorPerField() {
        JsonNode body = failure(
                client.post().uri("/decks").contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"name\": \" \", \"size\": 0}"),
                HttpStatus.BAD_REQUEST);

        assertEquals(List.of("DMD043 name", "DMD043 size"), codesAndFields(body));
        assertEquals("Invalid request: name must not be blank; size must be greater than 0", message(body));
    }

    @Test
    void malformedJsonIs400() {
        JsonNode body = failure(
                client.post().uri("/decks").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\": "),
                HttpStatus.BAD_REQUEST);

        assertEquals(List.of("DMD043 null"), codesAndFields(body));
    }

    @Test
    void theWrongMethodIs405AndSaysWhichAreAllowed() {
        var result = client.method(HttpMethod.DELETE).uri("/decks")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectBody(String.class)
                .returnResult();

        assertEquals(Set.of(HttpMethod.GET, HttpMethod.POST), result.getResponseHeaders().getAllow());
        assertEquals(List.of("DMD043 null"), codesAndFields(envelope(result.getResponseBody())));
    }

    @Test
    void theWrongContentTypeIs415() {
        JsonNode body = failure(
                client.post().uri("/decks").contentType(MediaType.TEXT_PLAIN).bodyValue("Sol Ring"),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        assertEquals(List.of("DMD043 null"), codesAndFields(body));
    }

    @Test
    void anUnexpectedExceptionIsStill500WithDmd001AndSaysNothingOfItsCause() {
        JsonNode body = failure(client.get().uri("/broken"), HttpStatus.INTERNAL_SERVER_ERROR);

        assertEquals(List.of("DMD001 null"), codesAndFields(body));
        assertEquals("An unexpected error occurred", message(body));
    }

    @Test
    void aGoodRequestIsUntouched() {
        client.get().uri("/decks?format=MODERN").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("MODERN page 0");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sends the request, expects {@code status} and a failed envelope with no payload. */
    private static JsonNode failure(WebTestClient.RequestHeadersSpec<?> request, HttpStatus status) {
        String body = request.exchange()
                .expectStatus().isEqualTo(status)
                .expectBody(String.class)
                .returnResult().getResponseBody();
        return envelope(body);
    }

    // Read as a tree and not as a DmdResponse: DmdResponse.setPayload sets success to true, so a
    // failure read back into one reports success.
    private static JsonNode envelope(String body) {
        assertNotNull(body);
        try {
            JsonNode envelope = JSON.readTree(body);
            assertFalse(envelope.get("success").asBoolean(), body);
            assertTrue(envelope.get("payload").isNull(), body);
            return envelope;
        } catch (JsonProcessingException e) {
            throw new AssertionError("Not JSON: " + body, e);
        }
    }

    private static String message(JsonNode envelope) {
        return envelope.get("message").asText();
    }

    /** Each error as "code field", in the order the envelope lists them. */
    private static List<String> codesAndFields(JsonNode envelope) {
        List<String> errors = new ArrayList<>();
        envelope.get("errors").forEach(error ->
                errors.add(error.get("error").asText() + " " + error.get("field").asText()));
        return errors;
    }
}
