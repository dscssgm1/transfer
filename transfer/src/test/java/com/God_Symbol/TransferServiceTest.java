package com.God_Symbol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TransferServiceTest {
  @TempDir Path storage;

  @Test
  void uploadPersistsMetadataStreamsFileAndEnforcesOwnership() throws Exception {
    DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
        .addScript("classpath:schema.sql").build();
    TransferService service = new TransferService(new TransferRepository(new JdbcTemplate(dataSource)), storage.toString(), 1024, Duration.ofHours(1));
    MockHttpServletResponse response = new MockHttpServletResponse();
    var result = service.upload(new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes()), null, response);
    String key = (String) result.get("key");
    assertTrue(key.matches("[A-Z2-9]{6}"));
    assertNotNull(response.getCookie("transfer_owner"));
    assertEquals(5L, service.get(key).size());
    assertTrue(Files.exists(storage.resolve(key + ".bin")));
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.delete(key, "not-the-owner"));
    service.delete(key, response.getCookie("transfer_owner").getValue());
    assertFalse(Files.exists(storage.resolve(key + ".bin")));
  }
}
