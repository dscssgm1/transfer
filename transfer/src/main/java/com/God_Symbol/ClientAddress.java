package com.God_Symbol;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录限流的客户端标识。
 *
 * <p>后端按部署约定只由 nginx 访问，因此信任 nginx 写入的 X-Forwarded-For；
 * 如果后端直接暴露公网，这个头可以被伪造，限流会被绕过。
 */
final class ClientAddress {
  private ClientAddress() {}

  static String key(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String first = forwarded.split(",")[0].trim();
      if (!first.isEmpty()) return first;
    }
    return request.getRemoteAddr();
  }
}
