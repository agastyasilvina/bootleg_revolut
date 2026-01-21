package com.bootleg.brevo.api;


public final class ApiModels {
  private ApiModels() {}

  public record ApiResponse<T>(
    String traceId,
    T data,
    String message
  ) {
    public static <T> ApiResponse<T> ok(String traceId, T data) {
      return new ApiResponse<>(traceId, data, "OK");
    }

    public static <T> ApiResponse<T> fail(String traceId, T data, String message) {
      return new ApiResponse<>(traceId, data, message);
    }
  }

  public record ApiError(
    String code,
    String message,
    String path
  ) {}
}
