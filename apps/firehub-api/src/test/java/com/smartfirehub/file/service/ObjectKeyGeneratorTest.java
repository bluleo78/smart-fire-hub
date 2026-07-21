package com.smartfirehub.file.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 오브젝트 키 생성/정제 규칙 검증(순수 단위 테스트). 키는 "&lt;prefix&gt;&lt;파일명&gt;" — S3 방식. */
class ObjectKeyGeneratorTest {

  final ObjectKeyGenerator gen = new ObjectKeyGenerator();

  @Test
  void sanitizeFilename_preservesRelativePathAndNormalizesSeparators() {
    // 폴더 구조(하위 경로)는 그대로 보존한다.
    assertThat(gen.sanitizeFilename("sub/dir/photo.jpg")).isEqualTo("sub/dir/photo.jpg");
    // '\\'는 '/'로 정규화된다(Windows 경로 대비).
    assertThat(gen.sanitizeFilename("a\\b\\c.md")).isEqualTo("a/b/c.md");
    // 선행 '/'(절대경로), 중복 '//', '.'(현재 디렉터리) 세그먼트는 흡수·제거된다.
    assertThat(gen.sanitizeFilename("/a//b/./c.txt")).isEqualTo("a/b/c.txt");
    // 각 세그먼트 앞뒤 공백 정리.
    assertThat(gen.sanitizeFilename("  report.md  ")).isEqualTo("report.md");
  }

  @Test
  void sanitizeFilename_rejectsTraversalSegments() {
    // ".." 세그먼트가 하나라도 있으면 traversal 시도로 보고 전체를 거부한다(빈 문자열 → 호출부 400).
    assertThat(gen.sanitizeFilename("../../etc/passwd")).isEmpty();
    assertThat(gen.sanitizeFilename("sub/../secret")).isEmpty();
    assertThat(gen.sanitizeFilename("a/b/../../../c")).isEmpty();
  }

  @Test
  void sanitizeFilename_emptyForNullBlankOrMeaninglessNames() {
    assertThat(gen.sanitizeFilename(null)).isEmpty();
    assertThat(gen.sanitizeFilename("")).isEmpty();
    assertThat(gen.sanitizeFilename("   ")).isEmpty();
    assertThat(gen.sanitizeFilename(".")).isEmpty();
    assertThat(gen.sanitizeFilename("..")).isEmpty();
    assertThat(gen.sanitizeFilename("/")).isEmpty();
    assertThat(gen.sanitizeFilename("//./")).isEmpty();
  }

  @Test
  void sanitizeFilename_preservesKoreanAndDotfileAndNestedKorean() {
    assertThat(gen.sanitizeFilename("보고서.md")).isEqualTo("보고서.md");
    assertThat(gen.sanitizeFilename(".gitignore")).isEqualTo(".gitignore");
    // 한글 폴더 경로도 그대로 보존.
    assertThat(gen.sanitizeFilename("사진/2026/보고서.md")).isEqualTo("사진/2026/보고서.md");
  }

  @Test
  void sanitizeFilename_capsUtf8BytesPreservingExtension() {
    // 한글 400자(1200B) + ".md" → 900B 상한 내로 잘리되 확장자는 보존한다.
    String out = gen.sanitizeFilename("가".repeat(400) + ".md");
    assertThat(out.getBytes(UTF_8).length).isLessThanOrEqualTo(900);
    assertThat(out).endsWith(".md");
  }

  @Test
  void generateKey_isPrefixPlusSanitizedRelativePath() {
    assertThat(gen.generateKey("equip/", "photo.jpg")).isEqualTo("equip/photo.jpg");
    assertThat(gen.generateKey("equip/", "보고서.md")).isEqualTo("equip/보고서.md");
    // 폴더 구조 보존: 하위 경로가 prefix 아래 그대로 이어진다.
    assertThat(gen.generateKey("equip/", "site-A/2026/cam1/img001.jpg"))
        .isEqualTo("equip/site-A/2026/cam1/img001.jpg");
  }

  @Test
  void generateKey_emptyOrTraversalFilenameYieldsBarePrefix() {
    // 정제 후 비면 prefix만 반환 → 호출부(컨트롤러)에서 400으로 거부해야 한다.
    assertThat(gen.generateKey("equip/", "..")).isEqualTo("equip/");
    assertThat(gen.generateKey("equip/", "../secret")).isEqualTo("equip/");
    assertThat(gen.generateKey("equip/", null)).isEqualTo("equip/");
  }
}
