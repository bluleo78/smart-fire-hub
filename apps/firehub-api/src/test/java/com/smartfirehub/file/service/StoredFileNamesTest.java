package com.smartfirehub.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.file.exception.StoragePathEscapeException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * {@link StoredFileNames#safeExtension(String)} 단위 테스트 (WD-610).
 *
 * <p>업로드 원본 파일명의 마지막 점 뒤를 저장 파일명에 그대로 붙이면 {@code /}·{@code ..}·초장문 확장자가 경로에 섞여 500 이 났다. 이제 마지막 점
 * 뒤가 {@code [a-z0-9]{1,10}}(소문자화 후)일 때만 확장자로 쓰고, 아니면 빈 확장자로 떨어뜨린다 — 글자를 지워 다른 이름을 만들지 않는다.
 */
class StoredFileNamesTest {

  /** 정상 확장자는 소문자로 유지된다. */
  @ParameterizedTest
  @CsvSource({
    "ok.txt, txt",
    "a.b/c.txt, txt",
    "A.PNG, png",
    "x.tar.gz, gz",
    ".hidden, hidden",
    "report.xlsx, xlsx",
    "conf.yaml, yaml",
    "photo.JPEG, jpeg",
    "'a\\b.txt', txt",
    "ten.abcdefghij, abcdefghij"
  })
  void keepsSafeExtension(String name, String expected) {
    assertThat(StoredFileNames.safeExtension(name)).isEqualTo(expected);
  }

  /** 경로 구분자·상위 경로·초장문·비영숫자·유니코드 확장자는 버린다(빈 문자열). */
  @ParameterizedTest
  @CsvSource({
    "report.v1/final",
    "../../etc/passwd",
    "'a.b\\c'",
    "noext",
    "trailingdot.",
    "a.한글",
    "eleven.abcdefghijk",
    "a.tx-t",
    "a.t t"
  })
  void dropsUnsafeExtension(String name) {
    assertThat(StoredFileNames.safeExtension(name)).isEmpty();
  }

  /** 300자 확장자(파일명 길이 초과로 500 이 나던 사례)도 버린다. */
  @Test
  void dropsVeryLongExtension() {
    assertThat(StoredFileNames.safeExtension("x." + "e".repeat(300))).isEmpty();
  }

  /** null·빈 이름은 빈 확장자. */
  @ParameterizedTest
  @NullAndEmptySource
  void nullOrEmptyGivesEmpty(String name) {
    assertThat(StoredFileNames.safeExtension(name)).isEmpty();
  }

  // ── resolveInside ───────────────────────────────────────────────────

  @TempDir Path dir;

  /** 어떤 원본 이름이든 저장 경로는 폴더 바로 아래 uuid[.ext] 다. */
  @ParameterizedTest
  @CsvSource({
    "report.v1/final, ''",
    "../../etc/passwd, ''",
    "A.PNG, .png",
    "noext, ''",
    "'a.b\\c', ''"
  })
  void resolveInsideStaysDirectlyUnderDir(String name, String suffix) {
    Path target = StoredFileNames.resolveInside(dir, name);

    assertThat(target.getParent()).isEqualTo(dir);
    assertThat(target.getFileName().toString())
        .matches("[0-9a-f-]{36}" + java.util.regex.Pattern.quote(suffix));
  }

  /** 예외 메시지에는 저장 파일명을 싣지 않는다(클라이언트로 새지 않게). */
  @Test
  void escapeExceptionMessageHasNoStoredName() {
    assertThatThrownBy(
            () -> {
              throw new StoragePathEscapeException();
            })
        .hasMessage("저장 경로가 저장 폴더를 벗어났습니다.");
  }

  // ── displayName ─────────────────────────────────────────────────────

  /** 255 코드포인트 이하는 그대로, null 은 null. */
  @Test
  void displayNameKeepsShortNames() {
    assertThat(StoredFileNames.displayName("ok.txt")).isEqualTo("ok.txt");
    assertThat(StoredFileNames.displayName("a".repeat(255))).hasSize(255);
    assertThat(StoredFileNames.displayName(null)).isNull();
  }

  /** 안전하지 않은 확장자(300자 꼬리)는 앞에서부터 255 코드포인트로 자른다. */
  @Test
  void displayNameTruncatesUnsafeTailPlainly() {
    String name = "x." + "e".repeat(300);
    assertThat(StoredFileNames.displayName(name)).isEqualTo(name.substring(0, 255));
  }

  /** 안전한 확장자는 살리고 앞부분만 잘라 255 에 맞춘다(원문 대소문자 유지). */
  @Test
  void displayNameKeepsSafeExtension() {
    String result = StoredFileNames.displayName("b".repeat(400) + ".PDF");
    assertThat(result).hasSize(255).endsWith(".PDF").startsWith("bbb");
  }

  /** 이모지(보조 평면, 서로게이트 쌍)를 가르지 않고 코드포인트 기준 255 로 자른다. */
  @Test
  void displayNameDoesNotSplitSurrogatePairs() {
    String emoji = "\uD83D\uDE00"; // 😀 — UTF-16 두 단위
    String name = emoji.repeat(300) + ".txt";
    String result = StoredFileNames.displayName(name);

    assertThat(result.codePointCount(0, result.length())).isEqualTo(255);
    assertThat(result).endsWith(".txt");
    assertThat(result.substring(0, result.length() - 4)).isEqualTo(emoji.repeat(251));
    // 꼬리 확장자가 안전하지 않으면 통째로 잘리는데, 이때도 마지막 글자가 반쪽 서로게이트가 아니어야 한다.
    String plain = StoredFileNames.displayName("a" + emoji.repeat(300));
    assertThat(plain.codePointCount(0, plain.length())).isEqualTo(255);
    assertThat(Character.isHighSurrogate(plain.charAt(plain.length() - 1))).isFalse();
  }
}
