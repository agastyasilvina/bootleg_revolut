package com.bootleg.brevo.api;

import com.bootleg.brevo.api.ApiModels.ApiResponse;
import com.bootleg.brevo.tracing.TraceIdProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class ApiResponseFactory {
  private final TraceIdProvider traceIdProvider;

  public ApiResponseFactory(TraceIdProvider traceIdProvider) {
    this.traceIdProvider = traceIdProvider;
  }

  public <T> Mono<ApiResponse<T>> ok(Mono<T> mono) {
    return mono.flatMap(data ->
      traceIdProvider.currentTraceId()
        .defaultIfEmpty("unknown")
        .map(traceId -> ApiResponse.ok(traceId, data))
    );
  }

  // This is the “best option” for your contract: wrapper + full list.
  public <T> Mono<ApiResponse<List<T>>> okList(Flux<T> flux) {
    return flux.collectList()
      .flatMap(list ->
        traceIdProvider.currentTraceId()
          .defaultIfEmpty("unknown")
          .map(traceId -> ApiResponse.ok(traceId, list))
      );
  }
}
