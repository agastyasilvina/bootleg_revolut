package com.example.otp;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/otp")
public class OtpController {

    private final OtpService otp;

    public OtpController(OtpService otp) {
        this.otp = otp;
    }

    @PostMapping("/request")
    public Mono<ResponseEntity<Map<String, Object>>> request(@RequestBody @Valid OtpRequest req) {
        return otp.request(req.msisdn())
            .map(attempt -> ResponseEntity.ok(Map.<String, Object>of(
                "status", "SENT",
                "attempt", attempt,
                "ttlSeconds", 300)))
            // handle the rate-limit outcomes right here, in the reactive chain
            .onErrorResume(OtpCooldownException.class,
                e -> Mono.just(retryAfter(e.retryAfter(), "COOLDOWN")))
            .onErrorResume(OtpBlockedException.class,
                e -> Mono.just(retryAfter(e.retryAfter(), "BLOCKED")));
    }

    @PostMapping("/verify")
    public Mono<ResponseEntity<Map<String, Object>>> verify(@RequestBody @Valid VerifyRequest req) {
        return otp.verify(req.msisdn(), req.code()).map(outcome -> switch (outcome) {
            case OK       -> ResponseEntity.ok(body("VERIFIED"));
            case INVALID  -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body("INVALID_CODE"));
            case EXPIRED  -> ResponseEntity.status(HttpStatus.GONE).body(body("CODE_EXPIRED"));
            case TOO_MANY -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body("TOO_MANY_ATTEMPTS"));
        });
    }

    private static ResponseEntity<Map<String, Object>> retryAfter(Duration d, String status) {
        long secs = Math.max(d.toSeconds(), 1);   // never advertise 0
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(secs))
            .body(Map.<String, Object>of("status", status, "retryAfterSeconds", secs));
    }

    private static Map<String, Object> body(String status) {
        return Map.of("status", status);
    }
}
