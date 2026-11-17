package com.smartfirehub.file.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 오브젝트 키 생성/정제 규칙 검증(순수 단위 테스트). 키는 "&lt;prefix&gt;&lt;파일명&gt;" — S3 방식. */
class ObjectKeyGeneratorTest {

  final ObjectKeyGenerator gen = new ObjectKeyGenerator();

  @Test
  void generateKey_preservesRelativePathAndNormalizesSeparators() {
    // 폴더 구조(하위 경로)는 그대로 보존한다.
    assertThat(gen.generateKey("p/", "sub/dir/photo.jpg")).isEqualTo("p/sub/dir/photo.jpg");
    // '\\'는 '/'로 정규화된다(Windows 경로 대비).
    assertThat(gen.generateKey("p/", "a\\b\\c.md")).isEqualTo("p/a/b/c.md");
    // 선행 '/'(절대경로), 중복 '//', '.'(현재 디렉터리) 세그먼트는 흡수·제거된다.
    assertThat(gen.generateKey("p/", "/a//b/./c.txt")).isEqualTo("p/a/b/c.txt");
    // 각 세그먼트 앞뒤 공백 정리.
    assertThat(gen.generateKey("p/", "  report.md  ")).isEqualTo("p/report.md");
  }

  @Test
  void generateKey_rejectsTraversalSegments() {
    // ".." 세그먼트가 하나라도 있으면 traversal 시도로 보고 전체를 거부한다.
    for (String bad : new String[] {"../../etc/passwd", "sub/../secret", "a/b/../../../c"}) {
      assertThatThrownBy(() -> gen.generateKey("p/", bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void generateKey_rejectsNullBlankOrMeaninglessNames() {
    for (String bad : new String[] {null, "", "   ", ".", "..", "/", "//./"}) {
      assertThatThrownBy(() -> gen.generateKey("p/", bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void generateKey_preservesKoreanAndDotfileAndNestedKorean() {
    assertThat(gen.generateKey("p/", "보고서.md")).isEqualTo("p/보고서.md");
    assertThat(gen.generateKey("p/", ".gitignore")).isEqualTo("p/.gitignore");
    // 한글 폴더 경로도 그대로 보존.
    assertThat(gen.generateKey("p/", "사진/2026/보고서.md")).isEqualTo("p/사진/2026/보고서.md");
  }

  /** WD-783: 앞부분이 같은 긴 이름 둘을 루트에서 자르면 같은 키가 되어 덮어쓴다 — 자르지 않고 둘 다 거부한다. */
  @Test
  void rootLongNamesWithSamePrefixAreRejectedNotTruncated() {
    for (String suffix : new String[] {"_v1.csv", "_v2.csv"}) {
      assertThatThrownBy(() -> gen.generateKey("equip/", "", "A".repeat(260) + suffix))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("255바이트");
    }
  }

  /** WD-783: 딱 255B 파일명은 루트에서도 그대로 받는다(경계). */
  @Test
  void rootSegmentExactly255BytesIsKeptAsIs() {
    String name = "x".repeat(251) + ".csv";
    assertThat(name.getBytes(UTF_8)).hasSize(255);
    assertThat(gen.generateKey("equip/", "", name)).isEqualTo("equip/" + name);
  }

  /** WD-783: 루트에서도 상대경로 합계가 900B 를 넘으면 자르지 않고 거부한다. */
  @Test
  void rootFolderTreeOver900BytesIsRejected() {
    String dirs = ("d".repeat(250) + "/").repeat(4); // 4×251 = 1004B > 900B (세그먼트는 각각 255B 이하)
    assertThatThrownBy(() -> gen.generateKey("equip/", "", dirs + "x.csv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("900바이트");
  }

  @Test
  void generateKey_isPrefixPlusSanitizedRelativePath() {
    assertThat(gen.generateKey("equip/", "photo.jpg")).isEqualTo("equip/photo.jpg");
    assertThat(gen.generateKey("equip/", "보고서.md")).isEqualTo("equip/보고서.md");
    // 폴더 구조 보존: 하위 경로가 prefix 아래 그대로 이어진다.
    assertThat(gen.generateKey("equip/", "site-A/2026/cam1/img001.jpg"))
        .isEqualTo("equip/site-A/2026/cam1/img001.jpg");
  }

  /** 정제 후 빈 이름(".." traversal·null 등)은 키를 만들지 않고 거부한다 — 업로드 URL 묶음은 그 파일만 실패로 싣는다(WD-776). */
  @Test
  void generateKey_emptyOrTraversalFilenameIsRejected() {
    assertThatThrownBy(() -> gen.generateKey("equip/", ".."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("유효한 파일명");
    assertThatThrownBy(() -> gen.generateKey("equip/", "../secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> gen.generateKey("equip/", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 폴더 경로 정규화(WD-761): 루트를 뜻하는 값(null·빈 문자열·'/' 만)은 빈 문자열이다. 공백뿐인 값은 루트가 아니라 이름이 공백인 폴더다(WD-776 —
   * normalizeFolderPath_preservesBackslashAndWhitespaceOnlySegments).
   */
  @Test
  void normalizeFolderPath_rootValuesBecomeEmpty() {
    assertThat(ObjectKeyGenerator.normalizeFolderPath(null)).isEmpty();
    assertThat(ObjectKeyGenerator.normalizeFolderPath("")).isEmpty();
    assertThat(ObjectKeyGenerator.normalizeFolderPath("/")).isEmpty();
    assertThat(ObjectKeyGenerator.normalizeFolderPath("//./")).isEmpty();
  }

  /** 폴더 경로는 세그먼트 단위로 정제하고 끝에 '/' 를 하나 붙여 돌려준다 — prefix 뒤에 그대로 이어 붙일 수 있게. */
  @Test
  void normalizeFolderPath_normalizesSegmentsAndAppendsSlash() {
    assertThat(ObjectKeyGenerator.normalizeFolderPath("a/b")).isEqualTo("a/b/");
    assertThat(ObjectKeyGenerator.normalizeFolderPath("a/b/")).isEqualTo("a/b/");
    // 선행 '/', 중복 '//', '.' 세그먼트는 흡수한다.
    assertThat(ObjectKeyGenerator.normalizeFolderPath("/a//./b/")).isEqualTo("a/b/");
    // 한글 보존. 세그먼트 이름은 다듬지 않는다 — 목록이 돌려준 실제 폴더(예: 끝에 공백이 붙은 「report 」)를 그대로 열 수 있어야 한다.
    assertThat(ObjectKeyGenerator.normalizeFolderPath("제품/Tibero7")).isEqualTo("제품/Tibero7/");
    assertThat(ObjectKeyGenerator.normalizeFolderPath("report /2026")).isEqualTo("report /2026/");
  }

  /** 폴더 경로의 제어문자는 지우지 않고 거부한다 — 지우면 사용자가 고르지 않은 다른 폴더를 가리키게 된다. */
  @Test
  void normalizeFolderPath_rejectsControlCharacters() {
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("제품\u0000/Tibero7"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("a/b\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** '..' 세그먼트는 데이터셋 prefix 밖으로 나가려는 시도이므로 400(IllegalArgumentException)으로 거부한다. */
  @Test
  void normalizeFolderPath_rejectsTraversal() {
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath(".."))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("../x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("a/../../b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("a\\..\\b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 900바이트(UTF-8) 상한을 넘는 폴더 경로는 잘라내지 않고 거부한다 — 잘린 경로는 다른 폴더를 가리키게 된다. */
  @Test
  void normalizeFolderPath_rejectsOverByteLimit() {
    // 한글 300자 = 900B, 끝 '/' 를 붙이면 901B → 거부.
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("가".repeat(300)))
        .isInstanceOf(IllegalArgumentException.class);
    // 세그먼트마다 255B 이하로 899B + '/' = 900B 는 허용(255+1 ×3 + 131+1).
    String exact = ("a".repeat(255) + "/").repeat(3) + "a".repeat(131) + "/";
    assertThat(ObjectKeyGenerator.normalizeFolderPath(exact)).isEqualTo(exact);
    // 한 글자 더(901B)면 거부.
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath(exact + "b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 폴더 지정 업로드 키: "&lt;prefix&gt;&lt;폴더&gt;&lt;파일 상대경로&gt;". 폴더가 없으면 기존 2인자 결과와 같다. */
  @Test
  void generateKey_withFolderPathPrependsFolder() {
    assertThat(gen.generateKey("equip/", "제품/Tibero7/", "a.jpg"))
        .isEqualTo("equip/제품/Tibero7/a.jpg");
    // 폴더 업로드의 하위 상대경로도 그대로 이어진다.
    assertThat(gen.generateKey("equip/", "a/", "sub/b.jpg")).isEqualTo("equip/a/sub/b.jpg");
    assertThat(gen.generateKey("equip/", "", "a.jpg")).isEqualTo("equip/a.jpg");
    // 파일명이 정제 후 비면 폴더가 있어도 거부한다(WD-776).
    assertThatThrownBy(() -> gen.generateKey("equip/", "a/", ".."))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * WD-776: 폴더 경로는 목록이 돌려준 실제 폴더를 그대로 가리켜야 한다 — '\' 를 '/' 로 바꾸지 않고(「report\2024」는 한 폴더), 공백뿐인 세그먼트도
   * 이름으로 보존한다. 빈 세그먼트('//')·'.' 는 지금처럼 흡수한다('.' 폴더는 키로 쓸 수 없어 목록에서 뺀다).
   */
  @Test
  void normalizeFolderPath_preservesBackslashAndWhitespaceOnlySegments() {
    assertThat(ObjectKeyGenerator.normalizeFolderPath("report\\2024")).isEqualTo("report\\2024/");
    assertThat(ObjectKeyGenerator.normalizeFolderPath("a\\b")).isEqualTo("a\\b/");
    assertThat(ObjectKeyGenerator.normalizeFolderPath(" ")).isEqualTo(" /");
    assertThat(ObjectKeyGenerator.normalizeFolderPath("   ")).isEqualTo("   /");
    assertThat(ObjectKeyGenerator.normalizeFolderPath("a/ /b")).isEqualTo("a/ /b/");
    // 빈 세그먼트·'.' 는 흡수 — 목록에 나오지 않는 이름이라 들어갈 폴더가 아니다.
    assertThat(ObjectKeyGenerator.normalizeFolderPath("a//b")).isEqualTo("a/b/");
    assertThat(ObjectKeyGenerator.normalizeFolderPath("a/./b")).isEqualTo("a/b/");
  }

  /** WD-776: '\' 를 보존해도 '\' 로 나뉜 조각이 '..' 면 거부한다 — Windows 경로로 읽히면 상위 경로가 되는 이름을 키에 넣지 않는다. */
  @Test
  void normalizeFolderPath_rejectsDotDotAroundBackslash() {
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("..\\x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("a\\.."))
        .isInstanceOf(IllegalArgumentException.class);
    // '..' 가 이름의 일부일 뿐이면 괜찮다.
    assertThat(ObjectKeyGenerator.normalizeFolderPath("v1..2\\x")).isEqualTo("v1..2\\x/");
  }

  /**
   * WD-776: 폴더를 지정한 업로드에서 폴더 + 파일 상대경로 합이 900B 를 넘으면 자르지 않고 그 파일만 거부한다(IllegalArgumentException —
   * 업로드 URL 묶음은 이를 그 파일의 실패로 싣는다). 잘라 올리면 앞부분이 같은 이름끼리 같은 키가 되어 말없이 덮어쓰고, 화면은 성공으로 보여 원래 이름으로 찾지
   * 못한다.
   */
  @Test
  void generateKey_withFolderPathRejectsOverByteLimitWithoutTruncating() {
    // 세그먼트는 모두 255B 이하 — 합계 900B 규칙만 본다(폴더 500B).
    String folder = ("a".repeat(249) + "/").repeat(2);
    assertThatThrownBy(
            () ->
                gen.generateKey("equip/", folder, "x".repeat(200) + "/" + "b".repeat(200) + ".jpg"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("경로가 너무 깁니다");
    // 하위 경로가 붙은 폴더 업로드도 같다(하위 폴더·파일명 어느 쪽도 자르지 않는다).
    assertThatThrownBy(
            () ->
                gen.generateKey(
                    "equip/", folder, "sub/" + "가".repeat(80) + "/" + "가".repeat(80) + ".md"))
        .isInstanceOf(IllegalArgumentException.class);
    // 딱 900B 는 그대로 받는다(500 + 200 + 1 + 199).
    String exact = "c".repeat(200) + "/" + "d".repeat(195) + ".jpg";
    assertThat(gen.generateKey("equip/", folder, exact)).isEqualTo("equip/" + folder + exact);
  }

  /**
   * WD-776: 목록에 보일 폴더인지 = normalizeFolderPath 가 그 상대 경로를 그대로 돌려주는지. 목록에 보이면 반드시 열리고, 정규화가 거부하거나 다른
   * 값으로 바꾸는 이름('.'·'..'·'\' 로 나눈 '..'·제어문자·빈 이름·상한 초과)은 목록에서 뺀다.
   */
  @Test
  void isOpenableFolder_matchesNormalizeFolderPathRoundTrip() {
    assertThat(ObjectKeyGenerator.isOpenableFolder("a/")).isTrue();
    assertThat(ObjectKeyGenerator.isOpenableFolder(" /")).isTrue();
    assertThat(ObjectKeyGenerator.isOpenableFolder("제품/report\\2024/")).isTrue();
    assertThat(ObjectKeyGenerator.isOpenableFolder("./")).isFalse();
    assertThat(ObjectKeyGenerator.isOpenableFolder("../")).isFalse();
    assertThat(ObjectKeyGenerator.isOpenableFolder("a\\..\\b/")).isFalse();
    assertThat(ObjectKeyGenerator.isOpenableFolder("x\u0001/")).isFalse();
    assertThat(ObjectKeyGenerator.isOpenableFolder("a//")).isFalse();
    assertThat(ObjectKeyGenerator.isOpenableFolder("a".repeat(900) + "/")).isFalse();
  }

  /**
   * WD-776: 폴더 안에서는 255B 를 넘는 파일명을 자르지 않고 그 파일만 거부한다 — 자르면 앞부분이 같은 두 파일이 같은 키가 되어 덮어쓴다. 255B 는 받고
   * 256B 는 거부한다.
   */
  @Test
  void generateKey_inFolderRejectsFilenameSegmentOver255Bytes() {
    String folder = "report\\2024/";
    // 한글 100자(300B) + ".txt" = 304B → 거부.
    assertThatThrownBy(() -> gen.generateKey("equip/", folder, "다".repeat(100) + ".txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("파일 이름이 너무 깁니다(최대 255바이트)");
    // 하위 경로의 마지막(파일명) 세그먼트도 같다.
    assertThatThrownBy(() -> gen.generateKey("equip/", folder, "sub/" + "x".repeat(252) + ".txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("파일 이름이 너무 깁니다");
    // 255B 는 그대로 받는다(251 + ".txt").
    String exact = "x".repeat(251) + ".txt";
    assertThat(gen.generateKey("equip/", folder, exact)).isEqualTo("equip/" + folder + exact);
  }

  /** WD-776: 파일 상대경로의 하위 폴더 이름이 255B 를 넘으면 루트·폴더 어디서든 그 파일을 거부한다 — 폴더 이름을 자르면 다른 폴더가 된다. */
  @Test
  void generateKey_rejectsSubfolderSegmentOver255Bytes() {
    String longDir = "d".repeat(256) + "/a.txt";
    assertThatThrownBy(() -> gen.generateKey("equip/", "", longDir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("폴더 이름이 너무 깁니다(최대 255바이트)");
    assertThatThrownBy(() -> gen.generateKey("equip/", "a/", longDir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("폴더 이름이 너무 깁니다");
    // 255B 하위 폴더는 받는다.
    assertThat(gen.generateKey("equip/", "", "d".repeat(255) + "/a.txt"))
        .isEqualTo("equip/" + "d".repeat(255) + "/a.txt");
  }

  /** WD-776: 폴더 경로의 세그먼트 하나가 255B 를 넘으면 거부한다(→ 400). 255B 는 받는다. */
  @Test
  void normalizeFolderPath_rejectsSegmentOver255Bytes() {
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("a".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("폴더 이름이 너무 깁니다(최대 255바이트)");
    assertThatThrownBy(() -> ObjectKeyGenerator.normalizeFolderPath("ok/" + "가".repeat(86)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ObjectKeyGenerator.normalizeFolderPath("ok/" + "가".repeat(85)))
        .isEqualTo("ok/" + "가".repeat(85) + "/");
    assertThat(ObjectKeyGenerator.isOpenableFolder("a".repeat(256) + "/")).isFalse();
  }
}
