package com.God_Symbol;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 multipart 的临时文件目录固定到文件存储目录。
 *
 * <p>默认情况下 Tomcat 会把上传内容先写入 {@code java.io.tmpdir}（通常是 /tmp）。
 * 本项目允许单文件接近 2 GiB，若 /tmp 是 tmpfs 或系统盘较小，上传会写爆系统分区。
 *
 * <p>这里直接使用 {@code transfer.storage} 解析后的绝对路径，而不是再读一次
 * TRANSFER_STORAGE 环境变量，避免「--transfer.storage 参数」与「环境变量」不一致时
 * 两者指向不同目录。
 */
@Configuration
public class MultipartConfig {
  @Bean
  MultipartConfigElement multipartConfigElement(
      @Value("${transfer.storage:./data}") String storage,
      @Value("${spring.servlet.multipart.max-file-size:2147483647B}") DataSize maxFileSize,
      @Value("${spring.servlet.multipart.max-request-size:2151677952B}") DataSize maxRequestSize) {
    String configured = storage == null || storage.isBlank() ? "./data" : storage;
    Path directory = Paths.get(configured).toAbsolutePath().normalize();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot create multipart temp directory: " + directory, e);
    }
    return new MultipartConfigElement(directory.toString(), maxFileSize.toBytes(), maxRequestSize.toBytes(), 0);
  }
}
