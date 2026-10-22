package com.God_Symbol;

import java.util.List;

/**
 * 文件传输功能的权限 Key。
 *
 * <p>这些 Key 同时用于接口上的 {@link RequiresPermission} 与启动时写入 {@code permissions}
 * 表的默认定义。管理员可以在权限页面调整授权，但 Key 本身必须与代码保持一致。
 */
public final class TransferPermissions {
  public static final String UPLOAD = "transfer.upload";
  public static final String DOWNLOAD = "transfer.download";
  public static final String DELETE = "transfer.delete";
  public static final String LIST = "transfer.list";
  public static final String QUERY = "transfer.query";

  public static List<Seed> defaults() {
    return List.of(
        new Seed(UPLOAD, "上传文件", "允许上传文件并获取验证码"),
        new Seed(DOWNLOAD, "下载文件", "允许申请下载链接并下载文件"),
        new Seed(DELETE, "删除文件", "允许删除文件"),
        new Seed(LIST, "查看我的文件", "允许列出当前浏览器上传的文件"),
        new Seed(QUERY, "验证码查询", "允许通过验证码查询文件信息"));
  }

  public record Seed(String code, String name, String description) {}

  private TransferPermissions() {}
}
