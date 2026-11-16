package com.smartfirehub.file.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  /** 폴더 경로 정규화(WD-761): 루트를 뜻하는 값(null·공백·'/' 만)은 빈 문자열이다. */
  @Test
  void normalizeFolderPath_rootValuesBecomeEmpty() {
    assertThat(gen.normalizeFolderPath(null)).isEmpty();
    assertThat(gen.normalizeFolderPath("")).isEmpty();
    assertThat(gen.normalizeFolderPath("   ")).isEmpty();
    assertThat(gen.normalizeFolderPath("/")).isEmpty();
    assertThat(gen.normalizeFolderPath("//./")).isEmpty();
  }

  /** 폴더 경로는 세그먼트 단위로 정제하고 끝에 '/' 를 하나 붙여 돌려준다 — prefix 뒤에 그대로 이어 붙일 수 있게. */
  @Test
  void normalizeFolderPath_normalizesSegmentsAndAppendsSlash() {
    assertThat(gen.normalizeFolderPath("a/b")).isEqualTo("a/b/");
    assertThat(gen.normalizeFolderPath("a/b/")).isEqualTo("a/b/");
    // 선행 '/', 중복 '//', '.' 세그먼트는 흡수한다.
    assertThat(gen.normalizeFolderPath("/a//./b/")).isEqualTo("a/b/");
    // '\' 는 '/' 로 정규화한다(Windows 경로 대비).
    assertThat(gen.normalizeFolderPath("a\\b")).isEqualTo("a/b/");
    // 한글 보존. 세그먼트 이름은 다듬지 않는다 — 목록이 돌려준 실제 폴더(예: 끝에 공백이 붙은 「report 」)를 그대로 열 수 있어야 한다.
    assertThat(gen.normalizeFolderPath("제품/Tibero7")).isEqualTo("제품/Tibero7/");
    assertThat(gen.normalizeFolderPath("report /2026")).isEqualTo("report /2026/");
  }

  /** 폴더 경로의 제어문자는 지우지 않고 거부한다 — 지우면 사용자가 고르지 않은 다른 폴더를 가리키게 된다. */
  @Test
  void normalizeFolderPath_rejectsControlCharacters() {
    assertThatThrownBy(() -> gen.normalizeFolderPath("제품\u0000/Tibero7"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> gen.normalizeFolderPath("a/b\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** '..' 세그먼트는 데이터셋 prefix 밖으로 나가려는 시도이므로 400(IllegalArgumentException)으로 거부한다. */
  @Test
  void normalizeFolderPath_rejectsTraversal() {
    assertThatThrownBy(() -> gen.normalizeFolderPath(".."))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> gen.normalizeFolderPath("../x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> gen.normalizeFolderPath("a/../../b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> gen.normalizeFolderPath("a\\..\\b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 900바이트(UTF-8) 상한을 넘는 폴더 경로는 잘라내지 않고 거부한다 — 잘린 경로는 다른 폴더를 가리키게 된다. */
  @Test
  void normalizeFolderPath_rejectsOverByteLimit() {
    // 한글 300자 = 900B, 끝 '/' 를 붙이면 901B → 거부.
    assertThatThrownBy(() -> gen.normalizeFolderPath("가".repeat(300)))
        .isInstanceOf(IllegalArgumentException.class);
    // 899B + '/' = 900B 는 허용.
    assertThat(gen.normalizeFolderPath("a".repeat(899))).isEqualTo("a".repeat(899) + "/");
  }

  /** 폴더 지정 업로드 키: "&lt;prefix&gt;&lt;폴더&gt;&lt;파일 상대경로&gt;". 폴더가 없으면 기존 2인자 결과와 같다. */
  @Test
  void generateKey_withFolderPathPrependsFolder() {
    assertThat(gen.generateKey("equip/", "제품/Tibero7/", "a.jpg"))
        .isEqualTo("equip/제품/Tibero7/a.jpg");
    // 폴더 업로드의 하위 상대경로도 그대로 이어진다.
    assertThat(gen.generateKey("equip/", "a/", "sub/b.jpg")).isEqualTo("equip/a/sub/b.jpg");
    assertThat(gen.generateKey("equip/", "", "a.jpg")).isEqualTo("equip/a.jpg");
    // 파일명이 정제 후 비면 prefix 만 — 호출부가 400 으로 거부한다(폴더가 붙지 않는다).
    assertThat(gen.generateKey("equip/", "a/", "..")).isEqualTo("equip/");
  }

  /** 폴더 + 파일 상대경로 합이 900B 를 넘으면 조용히 자르지 않고 400 으로 거부한다. */
  @Test
  void generateKey_withFolderPathRejectsOverByteLimit() {
    String folder = "a".repeat(500) + "/";
    assertThatThrownBy(() -> gen.generateKey("equip/", folder, "b".repeat(400) + ".jpg"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
