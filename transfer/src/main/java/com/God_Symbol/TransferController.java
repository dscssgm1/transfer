package com.God_Symbol;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
  private final TransferService service;
  public TransferController(TransferService service) { this.service = service; }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String,Object> upload(@RequestPart("file") MultipartFile file,
      @CookieValue(value="transfer_owner", required=false) String owner, HttpServletResponse response) throws IOException {
    return service.upload(file, owner, response);
  }

  @GetMapping
  public java.util.List<Map<String,Object>> list(@CookieValue(value="transfer_owner", required=false) String owner) {
    return service.list(owner).stream().map(t -> Map.<String,Object>of("key", t.key(), "name", t.name(), "size", t.size(), "expiresAt", t.expires().toString())).toList();
  }

  /** 公开接口：全站共享存储的已用 / 总量 / 剩余字节数。 */
  @GetMapping("/capacity")
  public Map<String,Object> capacity() { return service.capacity(); }

  @GetMapping("/{key}")
  public Map<String,Object> info(@PathVariable String key) {
    var t = service.get(key);
    return Map.of("key", t.key(), "name", t.name(), "size", t.size(), "expiresAt", t.expires().toString());
  }

  @GetMapping("/{key}/download")
  public void download(@PathVariable String key, HttpServletResponse response) throws IOException { service.download(service.get(key), response); }

  @DeleteMapping("/{key}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String key, @CookieValue(value="transfer_owner", required=false) String owner) throws IOException { service.delete(key, owner); }

  @Scheduled(fixedDelayString = "${transfer.cleanup-interval:1h}")
  public void cleanup() { service.cleanup(); }
}
