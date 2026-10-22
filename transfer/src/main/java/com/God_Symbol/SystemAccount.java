package com.God_Symbol;

/**
 * “未登录用户”系统账号的保留标识。
 *
 * <p>该账号代表所有匿名访客，它的授权就是匿名请求的权限。账号本身不能登录，
 * 也不能被删除、禁用或重置密码；管理员仍可在权限页面为它授权。
 */
public final class SystemAccount {
  public static final String USERNAME = "anonymous";
  /** 不是合法的 BCrypt 哈希，任何密码都无法匹配。 */
  public static final String PASSWORD_HASH = "!";
  public static final String CREATED_BY = "system";

  private SystemAccount() {}
}
