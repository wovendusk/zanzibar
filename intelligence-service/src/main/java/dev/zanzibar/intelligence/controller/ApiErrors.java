package dev.zanzibar.intelligence.controller;

import dev.zanzibar.intelligence.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** Turns failures of the services this one depends on into a 502 with a message. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler({IllegalStateException.class, RestClientException.class})
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse upstreamFailed(RuntimeException e) {
        return new ErrorResponse(e.getMessage());
    }
}
