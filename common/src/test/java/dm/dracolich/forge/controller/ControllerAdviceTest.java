package dm.dracolich.forge.controller;

import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.error.ErrorCode;
import dm.dracolich.forge.error.ErrorCodes;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.exception.ValidationException;
import dm.dracolich.forge.response.DmdResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControllerAdviceTest {

    private final ControllerAdvice advice = new ControllerAdvice();

    @Nested
    class HandleResponseException {

        @Test
        void returnsStatusAndMessage() {
            List<ApiError> errors = List.of(new ApiError(ErrorCodes.DMD001));
            ResponseException ex = new ResponseException("Bad input", errors, HttpStatus.BAD_REQUEST);

            ResponseEntity<DmdResponse<?>> response = advice.handleResponseException(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().getSuccess());
            assertEquals("Bad input", response.getBody().getMessage());
            assertEquals(1, response.getBody().getErrors().size());
            assertNull(response.getBody().getPayload());
        }

        @Test
        void handlesNullMessage() {
            List<ApiError> errors = List.of(new ApiError(ErrorCodes.DMD009));
            ResponseException ex = new ResponseException(errors, HttpStatus.NOT_FOUND);

            ResponseEntity<DmdResponse<?>> response = advice.handleResponseException(ex);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody().getMessage());
        }

        @Test
        void preservesHttpStatus() {
            ResponseException ex = new ResponseException("Denied", List.of(), HttpStatus.FORBIDDEN);

            ResponseEntity<DmdResponse<?>> response = advice.handleResponseException(ex);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, response.getBody().getHttpStatus());
        }
    }

    @Nested
    class HandleValidationException {

        @Test
        void returnsBadRequestWithMappedErrors() {
            List<ErrorCode> errorCodes = List.of(ErrorCodes.DMD003, ErrorCodes.DMD006);
            ValidationException ex = new ValidationException(errorCodes, "Validation failed");

            ResponseEntity<DmdResponse<?>> response = advice.handleValidationException(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().getSuccess());
            assertEquals("Validation failed", response.getBody().getMessage());
            assertEquals(2, response.getBody().getErrors().size());
            assertEquals("DMD003", response.getBody().getErrors().get(0).getError());
            assertEquals("DMD006", response.getBody().getErrors().get(1).getError());
        }

        @Test
        void handlesEmptyErrorList() {
            ValidationException ex = new ValidationException(List.of(), "No errors");

            ResponseEntity<DmdResponse<?>> response = advice.handleValidationException(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(response.getBody().getErrors().isEmpty());
        }
    }

    // What reaches these two handlers from a real request is covered in ControllerAdviceWebTest.
    // These are the cases a request can't easily produce.

    @Nested
    class HandleBindException {

        static class Deck {
            private String name;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
        }

        @SuppressWarnings("unused")
        void create(Deck deck) { }

        private WebExchangeBindException bindException(BeanPropertyBindingResult result) throws NoSuchMethodException {
            MethodParameter parameter = new MethodParameter(
                    HandleBindException.class.getDeclaredMethod("create", Deck.class), 0);
            return new WebExchangeBindException(parameter, result);
        }

        @Test
        void anErrorThatBelongsToNoFieldComesAfterTheFields() throws NoSuchMethodException {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Deck(), "deck");
            result.reject("Mismatch", "the commander does not fit the format");
            result.rejectValue("name", "NotBlank", "must not be blank");

            ResponseEntity<DmdResponse<?>> response = advice.handleBindException(bindException(result));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            List<ApiError> errors = response.getBody().getErrors();
            assertEquals(2, errors.size());
            assertEquals("DMD043", errors.get(0).getError());
            assertEquals("name", errors.get(0).getField());
            assertEquals("DMD043", errors.get(1).getError());
            assertNull(errors.get(1).getField());
            assertEquals("Invalid request: name must not be blank; the commander does not fit the format",
                    response.getBody().getMessage());
        }

        @Test
        void fieldsAreListedInAFixedOrder() throws NoSuchMethodException {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Deck(), "deck");
            result.rejectValue("name", "Size", "is too long");
            result.rejectValue("name", "Pattern", "has a character that is not allowed");

            ResponseEntity<DmdResponse<?>> response = advice.handleBindException(bindException(result));

            assertEquals("Invalid request: name is too long; name has a character that is not allowed",
                    response.getBody().getMessage());
        }
    }

    @Nested
    class HandleErrorResponseException {

        @Test
        void aStatusRaisedByHandKeepsItsStatusAndReason() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "No such deck");

            ResponseEntity<DmdResponse<?>> response = advice.handleErrorResponseException(ex);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND, response.getBody().getHttpStatus());
            assertEquals("Invalid request: No such deck", response.getBody().getMessage());
            assertEquals("DMD043", response.getBody().getErrors().getFirst().getError());
            assertNull(response.getBody().getErrors().getFirst().getField());
        }

        @Test
        void aStatusWithNoReasonUsesTheStatusName() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.CONFLICT);

            ResponseEntity<DmdResponse<?>> response = advice.handleErrorResponseException(ex);

            assertEquals("Invalid request: Conflict", response.getBody().getMessage());
        }

        @Test
        void aServerSideStatusIsReportedLikeAnyUnexpectedFailure() {
            ResponseStatusException ex = new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "mongo-0.internal:27017 refused the connection");

            ResponseEntity<DmdResponse<?>> response = advice.handleErrorResponseException(ex);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertEquals("An unexpected error occurred", response.getBody().getMessage());
            assertEquals(1, response.getBody().getErrors().size());
            assertEquals("DMD001", response.getBody().getErrors().getFirst().getError());
        }

        @Test
        void aStatusCodeTheEnvelopeHasNoNameForIsSentAsItIs() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatusCode.valueOf(499), "Closed", null);

            ResponseEntity<DmdResponse<?>> response = advice.handleErrorResponseException(ex);

            assertEquals(499, response.getStatusCode().value());
            assertEquals(HttpStatus.BAD_REQUEST, response.getBody().getHttpStatus());
            assertEquals("DMD043", response.getBody().getErrors().getFirst().getError());
        }

        @Test
        void aBodyThatCannotBeReadNamesNoField() throws NoSuchMethodException {
            MethodParameter body = new MethodParameter(
                    HandleBindException.class.getDeclaredMethod("create", HandleBindException.Deck.class), 0);
            ServerWebInputException ex = new ServerWebInputException("Failed to read HTTP message", body,
                    new IllegalArgumentException("Unexpected end-of-input at line 1, column 9"));

            ResponseEntity<DmdResponse<?>> response = advice.handleErrorResponseException(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNull(response.getBody().getErrors().getFirst().getField());
            assertEquals("Invalid request: Failed to read HTTP message", response.getBody().getMessage());
        }
    }

    @Nested
    class HandleGenericException {

        @Test
        void returns500WithDmd001Error() {
            Exception ex = new RuntimeException("Something unexpected");

            ResponseEntity<DmdResponse<?>> response = advice.handleGenericException(ex);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().getSuccess());
            assertEquals("An unexpected error occurred", response.getBody().getMessage());
            assertEquals(1, response.getBody().getErrors().size());
            assertEquals("DMD001", response.getBody().getErrors().getFirst().getError());
        }
    }
}
