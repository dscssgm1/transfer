package com.God_Symbol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Map;

/**
 * 统一 API 错误响应。
 *
 * <p>继承 {@link ResponseEntityExceptionHandler}，让 Spring MVC 自身的异常保留正确状态码：
 * 缺少 multipart part → 400，Content-Type 不支持 → 415，方法不支持 → 405，
 * 静态资源缺失 → 404。此前只显式匹配少数异常，其余全部落入兜底并被误报为 500。
 *
 * <p>父类已经为这些异常注册了统一的 {@code handleException} 映射，因此这里只能覆写
 * 其受保护的钩子方法，不能再重复声明同类型的 {@code @ExceptionHandler}，否则启动时
 * 会因映射冲突而失败。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** 文件超过容器/接口允许的大小，应返回 413 而不是 500。 */
  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    log.warn("rejected oversized upload: {}", ex.getMessage());
    HttpStatus tooLarge = HttpStatus.PAYLOAD_TOO_LARGE;
    return new ResponseEntity<>(body(tooLarge, "file exceeds the 2 GiB limit"), headers, tooLarge);
  }

  /** 业务层抛出的 ResponseStatusException，保留其自定义原因文本。 */
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> businessError(org.springframework.web.server.ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String reason = ex.getReason();
    if (status.is5xxServerError()) log.error("request failed with {}", status.value(), ex);
    return ResponseEntity.status(status).body(body(status, reason == null ? status.getReasonPhrase() : reason));
  }

  /** 缺少 file 部分时给出可操作的提示，而不是笼统的 Bad Request。 */
  @Override
  protected ResponseEntity<Object> handleMissingServletRequestPart(
      org.springframework.web.multipart.support.MissingServletRequestPartException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    HttpStatus badRequest = HttpStatus.BAD_REQUEST;
    return new ResponseEntity<>(body(badRequest, "required multipart part '" + ex.getRequestPartName() + "' is missing"), headers, badRequest);
  }

  /** Content-Type 不是 multipart/form-data 时说明正确用法。 */
  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
      org.springframework.web.HttpMediaTypeNotSupportedException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    HttpStatus unsupported = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    return new ResponseEntity<>(body(unsupported,
        "unsupported Content-Type: use application/json for admin endpoints or multipart/form-data for uploads"),
        headers, unsupported);
  }

  /**
   * 方法级鉴权（@PreAuthorize）抛出的拒绝不会经过 Security 过滤器，
   * 若不单独处理会被兜底 Exception 处理器误报为 500。
   */
  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<Map<String, Object>> accessDenied(
      org.springframework.security.access.AccessDeniedException ex) {
    // 匿名请求按“未登录用户”系统账号授权，因此这里统一是 403；
    // 需要登录的接口（/api/user/**、/api/admin/**）由过滤器链返回 401/403。
    org.springframework.security.core.Authentication authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    boolean anonymous = authentication == null
        || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
    HttpStatus status = HttpStatus.FORBIDDEN;
    return ResponseEntity.status(status).body(body(status,
        anonymous ? "anonymous access is not permitted for this operation" : "insufficient permissions"));
  }

  /** 认证异常统一返回 401，避免被兜底逻辑包装成 500。 */
  @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
  ResponseEntity<Map<String, Object>> authenticationError(
      org.springframework.security.core.AuthenticationException ex) {
    HttpStatus status = HttpStatus.UNAUTHORIZED;
    return ResponseEntity.status(status).body(body(status, "authentication required"));
  }

  /** 兜底：未预期异常返回 500，记录完整堆栈，响应体不泄露内部细节。 */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
    // 客户端取消上传会让 multipart 解析抛出连接重置；这是正常操作，
    // 只记一条 info，不写错误堆栈。状态码取 nginx 约定的 499（客户端已关闭连接）。
    if (isClientAbort(ex)) {
      log.info("client aborted the request");
      return ResponseEntity.status(499).build();
    }
    log.error("unexpected error while handling request", ex);
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status).body(body(status, "transfer operation failed"));
  }

  private boolean isClientAbort(Throwable ex) {
    for (Throwable current = ex; current != null; current = current.getCause()) {
      if (current instanceof org.apache.catalina.connector.ClientAbortException) return true;
      String message = current.getMessage();
      if (message != null && (message.contains("Connection reset") || message.contains("Broken pipe"))) return true;
    }
    return false;
  }

  /**
   * 所有框架异常最终都会走到这里（含 ResponseStatusException 等 ErrorResponseException），
   * 在此把响应体转换为项目统一的 JSON 形状，并保留原始状态码与响应头。
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object responseBody,
      HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (statusCode.is5xxServerError()) log.error("request failed with {}", statusCode.value(), ex);
    HttpStatus status = HttpStatus.valueOf(statusCode.value());
    String message = responseBody instanceof ProblemDetail detail && detail.getDetail() != null
        ? detail.getDetail()
        : status.getReasonPhrase();
    return new ResponseEntity<>(body(status, message), headers, statusCode);
  }

  private Map<String, Object> body(HttpStatus status, String message) {
    return Map.of(
        "status", status.value(),
        "error", status.getReasonPhrase(),
        "message", message,
        "timestamp", Instant.now().toString());
  }
}
