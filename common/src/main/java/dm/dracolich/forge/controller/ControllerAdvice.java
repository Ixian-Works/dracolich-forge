package dm.dracolich.forge.controller;

import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.error.ErrorCodes;
import dm.dracolich.forge.error.ErrorSeverity;
import dm.dracolich.forge.response.DmdResponse;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ServerWebInputException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class ControllerAdvice {
    @ExceptionHandler(ResponseException.class)
    public ResponseEntity<DmdResponse<?>> handleResponseException(ResponseException ex) {
        return new ResponseEntity<>(new DmdResponse<>(false, null, ex.getErrors(),
                ex.getHttpStatus(), ex.getMessage()), ex.getHttpStatus());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<DmdResponse<?>> handleValidationException(ValidationException ex) {
        List<ApiError> errors = ex.getErrors().stream()
                .map(ApiError::new)
                .toList();
        return new ResponseEntity<>(new DmdResponse<>(false, null, errors,
                HttpStatus.BAD_REQUEST, ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    /**
     * A request body that failed validation. One error per rejected field, with {@code field}
     * set, so a client can mark the field. Errors that belong to no field come last.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<DmdResponse<?>> handleBindException(WebExchangeBindException ex) {
        List<ApiError> errors = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        // Sorted, because the order validators report fields in is not fixed.
        ex.getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .forEach(error -> {
                    errors.add(new ApiError(ErrorCodes.DMD043, ErrorSeverity.ERROR, error.getField()));
                    reasons.add(error.getField() + " " + error.getDefaultMessage());
                });
        for (ObjectError error : ex.getGlobalErrors()) {
            errors.add(new ApiError(ErrorCodes.DMD043));
            reasons.add(String.valueOf(error.getDefaultMessage()));
        }
        if (errors.isEmpty()) {
            errors.add(new ApiError(ErrorCodes.DMD043));
            reasons.add(reasonOf(ex));
        }
        return rejected(ex, errors, ErrorCodes.DMD043.format(String.join("; ", reasons)));
    }

    /**
     * Every other failure Spring raises with a status of its own: a parameter that is missing or
     * can't be converted (400), a body that can't be read (400), the wrong method (405), the wrong
     * content type (415) and the rest of {@code ResponseStatusException}. The status Spring chose
     * is kept, with the headers that go with it, such as {@code Allow} on a 405.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<DmdResponse<?>> handleErrorResponseException(ErrorResponseException ex) {
        if (!ex.getStatusCode().is4xxClientError()) {
            // Not the caller's mistake, so it says no more than any unexpected failure does.
            log.error("Unhandled exception", ex);
            return rejected(ex, List.of(new ApiError(ErrorCodes.DMD001)), "An unexpected error occurred");
        }
        String field = requestValueName(ex);
        ApiError error = field != null
                ? new ApiError(ErrorCodes.DMD043, ErrorSeverity.ERROR, field)
                : new ApiError(ErrorCodes.DMD043);
        return rejected(ex, List.of(error), ErrorCodes.DMD043.format(reasonOf(ex)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<DmdResponse<?>> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        List<ApiError> errors = List.of(new ApiError(ErrorCodes.DMD001));
        return new ResponseEntity<>(new DmdResponse<>(false, null, errors,
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<DmdResponse<?>> rejected(ErrorResponse ex, List<ApiError> errors, String message) {
        HttpStatusCode status = ex.getStatusCode();
        // The envelope holds an HttpStatus. A code outside that enum is reported as its class.
        HttpStatus known = HttpStatus.resolve(status.value());
        if (known == null) {
            known = status.is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return new ResponseEntity<>(new DmdResponse<>(false, null, errors, known, message),
                ex.getHeaders(), status);
    }

    // Spring's own one-line reason: "Type mismatch.", "Required query parameter 'page' is not
    // present.", "Request method 'DELETE' is not supported." It never carries the cause.
    private static String reasonOf(ErrorResponse ex) {
        String detail = ex.getBody().getDetail();
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        HttpStatus known = HttpStatus.resolve(ex.getStatusCode().value());
        return known != null ? known.getReasonPhrase() : "Request rejected";
    }

    /**
     * The name of the query parameter, path variable, header or cookie a failure is about, or
     * null when it is about the request as a whole, such as a body that can't be read.
     */
    private static String requestValueName(ErrorResponseException ex) {
        if (ex instanceof MissingRequestValueException missing) {
            return missing.getName();
        }
        if (!(ex instanceof ServerWebInputException input) || input.getMethodParameter() == null) {
            return null;
        }
        MethodParameter parameter = input.getMethodParameter();
        String declared = declaredName(parameter);
        if (declared == null) {
            // Not annotated: Spring binds a simple type by its parameter name, and anything else
            // (a body, a model object) is not one request value.
            return BeanUtils.isSimpleProperty(parameter.getParameterType()) ? parameter.getParameterName() : null;
        }
        return declared.isEmpty() ? parameter.getParameterName() : declared;
    }

    // The name given in the annotation that binds one request value; "" when the annotation is
    // there without a name; null when there is no such annotation.
    private static String declaredName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) return firstNonEmpty(requestParam.name(), requestParam.value());
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) return firstNonEmpty(pathVariable.name(), pathVariable.value());
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) return firstNonEmpty(requestHeader.name(), requestHeader.value());
        CookieValue cookieValue = parameter.getParameterAnnotation(CookieValue.class);
        if (cookieValue != null) return firstNonEmpty(cookieValue.name(), cookieValue.value());
        MatrixVariable matrixVariable = parameter.getParameterAnnotation(MatrixVariable.class);
        if (matrixVariable != null) return firstNonEmpty(matrixVariable.name(), matrixVariable.value());
        return null;
    }

    private static String firstNonEmpty(String first, String second) {
        return !first.isEmpty() ? first : second;
    }
}
