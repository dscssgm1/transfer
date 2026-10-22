package com.God_Symbol;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
  private final TransferService service;
  private final DownloadTicketService tickets;
  private final PermissionGuard permissionGuard;

  public TransferController(TransferService service, DownloadTicketService tickets,
      PermissionGuard permissionGuard) {
    this.service = service;
    this.tickets = tickets;
    this.permissionGuard = permissionGuard;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RequiresPermission(TransferPermissions.UPLOAD)
  public Map<String,Object> upload(@RequestPart("file") MultipartFile file,
      @CookieValue(value="transfer_owner", required=false) String owner, HttpServletResponse response) throws IOException {
    return service.upload(file, owner, response);
  }

  @GetMapping
  @RequiresPermission(TransferPermissions.LIST)
  public java.util.List<Map<String,Object>> list(@CookieValue(value="transfer_owner", required=false) String owner) {
    return service.list(owner).stream().map(t -> Map.<String,Object>of("key", t.key(), "name", t.name(), "size", t.size(), "expiresAt", t.expires().toString())).toList();
  }

  /** 公开接口：全站共享存储的已用 / 总量 / 剩余字节数。 */
  @GetMapping("/capacity")
  public Map<String,Object> capacity() { return service.capacity(); }

  @GetMapping("/{key}")
  @RequiresPermission(TransferPermissions.QUERY)
  public Map<String,Object> info(@PathVariable String key) {
    var t = service.get(key);
    return Map.of("key", t.key(), "name", t.name(), "size", t.size(), "expiresAt", t.expires().toString());
  }

  /**
   * 为浏览器下载换取短时票据。浏览器无法在 {@code <a href>} 上附加 Bearer 头，
   * 因此先通过这个需要 {@code transfer.download} 权限的接口拿到票据。
   */
  @PostMapping("/{key}/download-ticket")
  @RequiresPermission(TransferPermissions.DOWNLOAD)
  public ResponseEntity<Map<String,Object>> downloadTicket(@PathVariable String key) {
    service.get(key); // 未知或已过期的文件不签发票据
    DownloadTicketService.IssuedTicket ticket = tickets.issue(key);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("url", ticket.url(), "expiresAt", ticket.expiresAt().toString()));
  }

  /**
   * 有效票据（或当前身份的 {@code transfer.download} 权限）即可下载。
   * 保留权限回退分支，便于 curl / API 客户端直接带 Bearer 令牌。
   */
  @GetMapping("/{key}/download")
  public void download(@PathVariable String key,
      @RequestParam(value = "ticket", required = false) String ticket,
      Authentication authentication, HttpServletResponse response) throws IOException {
    if (!tickets.verify(key, ticket)
        && !permissionGuard.has(authentication, TransferPermissions.DOWNLOAD)) {
      throw new AccessDeniedException("insufficient permissions");
    }
    service.download(service.get(key), response);
  }

  @DeleteMapping("/{key}")
  @RequiresPermission(TransferPermissions.DELETE)
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String key, @CookieValue(value="transfer_owner", required=false) String owner) throws IOException { service.delete(key, owner); }

  @Scheduled(fixedDelayString = "${transfer.cleanup-interval:1h}")
  public void cleanup() { service.cleanup(); }
}
