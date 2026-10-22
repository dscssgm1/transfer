package com.God_Symbol;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个功能所需的权限 Key。
 *
 * <pre>{@code
 * @RequiresPermission("transfer.manage")
 * @GetMapping("/api/user/reports")
 * public List<Report> reports() { ... }
 * }</pre>
 *
 * <p>Key 必须与 {@code permissions.code} 完全一致；管理员账号绕过校验。
 * 由 {@link PermissionAspect} 在方法调用前实时查库，撤销后立即生效。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {
  String value();
}
