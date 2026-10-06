package dev.zanzibar.acl.controller;

import dev.zanzibar.acl.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns "the request was wrong" exceptions thrown anywhere in a controller
 * into a 400 response with a message, instead of the default 500.
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(RuntimeException e) {
        return new ErrorResponse(e.getMessage());
    }
}
