package com.bootleg.brevo.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class TraceIdProvider {
  private final Tracer tracer;

  public TraceIdProvider(Tracer tracer) {
    this.tracer = tracer;
  }

  public Mono<String> currentTraceId() {
    // Runs inside reactive chain
    return Mono.defer(() ->
      Mono.justOrEmpty(Optional.ofNullable(tracer.currentSpan())
        .map(Span::context)
        .map(ctx -> ctx.traceId()))
    );
  }
}
