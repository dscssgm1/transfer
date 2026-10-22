package com.God_Symbol;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 让 {@link RequiresPermission} 与 {@code @PreAuthorize} 一样在方法执行前校验功能权限。
 *
 * <p>权限本身由 {@link PermissionGuard} 实时查库；这里只负责读取注解上的 Key。
 */
@Aspect
@Component
public class PermissionAspect {
  private final PermissionGuard permissionGuard;

  public PermissionAspect(PermissionGuard permissionGuard) {
    this.permissionGuard = permissionGuard;
  }

  @Before("@annotation(com.God_Symbol.RequiresPermission)")
  public void requirePermission(JoinPoint joinPoint) {
    RequiresPermission required = annotationOf(joinPoint);
    if (required == null) return;

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (permissionGuard.has(authentication, required.value())) return;

    // 匿名请求也会按“未登录用户”系统账号校验，所以失败一律是权限不足（403）。
    throw new AccessDeniedException("insufficient permissions");
  }

  private static RequiresPermission annotationOf(JoinPoint joinPoint) {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    RequiresPermission required = method.getAnnotation(RequiresPermission.class);
    if (required != null) return required;
    // 通过接口代理调用时，注解可能只存在于实现类的方法上。
    try {
      Method target = joinPoint.getTarget().getClass()
          .getMethod(method.getName(), method.getParameterTypes());
      return target.getAnnotation(RequiresPermission.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }
}
