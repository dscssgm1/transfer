package com.God_Symbol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** 文件超过容器/接口允许的大小，应返回 413 而不是 500。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ex) {
    log.warn("rejected oversized upload: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(body(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds the 2 GiB limit"));
  }

  /**
   * 处理携带 HTTP 状态语义的异常，包括业务抛出的 ResponseStatusException 以及框架抛出的
   * NoResourceFoundException（静态资源缺失）等。若不在 {@link #unexpected} 之前拦截，
   * 静态资源 404 会被统一当成 500 返回。
   */
  @ExceptionHandler({ResponseStatusException.class, ErrorResponseException.class, NoResourceFoundException.class})
  ResponseEntity<Map<String, Object>> errorResponse(Exception ex) {
    HttpStatus status = HttpStatus.valueOf(((ErrorResponse) ex).getStatusCode().value());
    String detail = ((ErrorResponse) ex).getBody().getDetail();
    if (status.is5xxServerError()) log.error("request failed with {}", status.value(), ex);
    return ResponseEntity.status(status).body(body(status, detail == null ? status.getReasonPhrase() : detail));
  }

  /**
   * 兜底处理未预期异常。必须记录日志，否则线上故障会完全静默、无从排查。
   * 响应体不包含内部细节。
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
    log.error("unexpected error while handling request", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "transfer operation failed"));
  }

  private Map<String, Object> body(HttpStatus status, String message) {
    return Map.of(
        "status", status.value(),
        "error", status.getReasonPhrase(),
        "message", message,
        "timestamp", Instant.now().toString());
  }
}
