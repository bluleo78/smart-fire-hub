package com.smartfirehub.file.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 오브젝트 키 생성/정제 규칙 검증(순수 단위 테스트). 키는 "&lt;prefix&gt;&lt;파일명&gt;" — S3 방식. */
class ObjectKeyGeneratorTest {

  final ObjectKeyGenerator gen = new ObjectKeyGenerator();

  @Test
  void sanitizeFilename_takesBasenameAndStripsTraversalAndTrims() {
    // 경로가 섞여 와도 basename만 취해 프리픽스 밖으로 못 나간다('/'·'\\' 모두).
    assertThat(gen.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
    assertThat(gen.sanitizeFilename("a\\b\\c.md")).isEqualTo("c.md");
    assertThat(gen.sanitizeFilename("  report.md  ")).isEqualTo("report.md");
  }

  @Test
  void sanitizeFilename_emptyForNullBlankOrMeaninglessNames() {
    assertThat(gen.sanitizeFilename(null)).isEmpty();
    assertThat(gen.sanitizeFilename("")).isEmpty();
    assertThat(gen.sanitizeFilename("   ")).isEmpty();
    assertThat(gen.sanitizeFilename(".")).isEmpty();
    assertThat(gen.sanitizeFilename("..")).isEmpty();
    assertThat(gen.sanitizeFilename("/")).isEmpty();
  }

  @Test
  void sanitizeFilename_preservesKoreanAndDotfile() {
    assertThat(gen.sanitizeFilename("보고서.md")).isEqualTo("보고서.md");
    assertThat(gen.sanitizeFilename(".gitignore")).isEqualTo(".gitignore");
  }

  @Test
  void sanitizeFilename_capsUtf8BytesPreservingExtension() {
    // 한글 100자(300B) + ".md" → 200B 상한 내로 잘리되 확장자는 보존한다.
    String out = gen.sanitizeFilename("가".repeat(100) + ".md");
    assertThat(out.getBytes(UTF_8).length).isLessThanOrEqualTo(200);
    assertThat(out).endsWith(".md");
  }

  @Test
  void generateKey_isPrefixPlusSanitizedFilename() {
    assertThat(gen.generateKey("equip/", "photo.jpg")).isEqualTo("equip/photo.jpg");
    assertThat(gen.generateKey("equip/", "보고서.md")).isEqualTo("equip/보고서.md");
    // 경로 주입 시도는 basename만 남아 프리픽스 하위에 머문다.
    assertThat(gen.generateKey("equip/", "../secret")).isEqualTo("equip/secret");
  }

  @Test
  void generateKey_emptyFilenameYieldsBarePrefix() {
    // 정제 후 비면 prefix만 반환 → 호출부(컨트롤러)에서 400으로 거부해야 한다.
    assertThat(gen.generateKey("equip/", "..")).isEqualTo("equip/");
    assertThat(gen.generateKey("equip/", null)).isEqualTo("equip/");
  }
}
