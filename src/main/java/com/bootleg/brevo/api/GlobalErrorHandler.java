package com.bootleg.brevo.api;

import com.bootleg.brevo.api.ApiModels.ApiError;
import com.bootleg.brevo.api.ApiModels.ApiResponse;
import com.bootleg.brevo.tracing.TraceIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalErrorHandler {

  private final TraceIdProvider traceIdProvider;

  public GlobalErrorHandler(TraceIdProvider traceIdProvider) {
    this.traceIdProvider = traceIdProvider;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public Mono<ResponseEntity<ApiResponse<ApiError>>> handleStatus(
    ResponseStatusException ex,
    ServerWebExchange exchange
  ) {
    int statusCode = ex.getStatusCode().value();
    HttpStatus status = HttpStatus.valueOf(statusCode);
    String path = exchange.getRequest().getPath().value();

    String msg = (ex.getReason() != null && !ex.getReason().isBlank())
      ? ex.getReason()
      : ex.getMessage();

    return traceIdProvider.currentTraceId()
      .defaultIfEmpty("unknown")
      .map(traceId -> {
        ApiError err = new ApiError("HTTP_" + statusCode, msg, path);
        ApiResponse<ApiError> body = ApiResponse.fail(traceId, err, "ERROR");
        return ResponseEntity.status(status).body(body);
      });
  }

  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<ApiResponse<ApiError>>> handleAny(
    Exception ex,
    ServerWebExchange exchange
  ) {
    String path = exchange.getRequest().getPath().value();

    return traceIdProvider.currentTraceId()
      .defaultIfEmpty("unknown")
      .map(traceId -> {
        ApiError err = new ApiError("INTERNAL_ERROR", ex.getMessage(), path);
        ApiResponse<ApiError> body = ApiResponse.fail(traceId, err, "ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
      });
  }
}
