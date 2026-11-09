package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link DocumentStorageService#store(byte[], String)} 저장 파일명 검증 (WD-610).
 *
 * <p>예전 구현은 확장자에서 영숫자 아닌 글자를 지워 붙였다({@code v1/final} → {@code v1final}). 이제 FileUploadService 와 같은
 * 공용 규칙으로 「맞지 않으면 버림」(10자 상한)이고, 저장 파일은 언제나 documents/{date}/ 안에 놓인다.
 */
class DocumentStorageServiceTest {

  @TempDir Path uploadRoot;

  /** 원본 이름이 무엇이든 documents 폴더 안에 uuid[.ext] 로 저장되고 내용이 그대로다. */
  @ParameterizedTest
  @CsvSource({
    "report.v1/final, ''",
    "../../etc/passwd, ''",
    "noext, ''",
    "A.PDF, .pdf",
    "a.b/c.txt, .txt",
    "eleven.abcdefghijk, ''",
    "a.한글, ''"
  })
  void storesInsideDocumentsDirWithSafeName(String originalName, String expectedSuffix)
      throws Exception {
    DocumentStorageService service = new DocumentStorageService(uploadRoot.toString());

    Path stored = Path.of(service.store("hello".getBytes(), originalName));

    Path documentsDir = uploadRoot.resolve("documents").toAbsolutePath().normalize();
    assertThat(stored.normalize().getParent().getParent()).isEqualTo(documentsDir);
    assertThat(stored.getFileName().toString())
        .matches("[0-9a-f-]{36}" + java.util.regex.Pattern.quote(expectedSuffix));
    assertThat(Files.readString(stored)).isEqualTo("hello");
  }
}
