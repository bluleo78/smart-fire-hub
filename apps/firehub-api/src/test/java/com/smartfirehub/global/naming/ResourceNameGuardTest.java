package com.smartfirehub.global.naming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** WD-824 — 이름 정규화·변경 판정·복제 이름 규칙(순수 로직). */
class ResourceNameGuardTest {

  @Test
  void normalize_stripsAndLowercases() {
    assertThat(ResourceNameGuard.normalize("  Sales ")).isEqualTo("sales");
  }

  /** WD-1108: DB 가 같게 보는 이름은 같은 잠금 키 — 안쪽 전각 공백·앞뒤 전각 공백도 ASCII 공백으로 맞춘다. */
  @Test
  void normalize_unicodeSpacesShareLockKeyWithAscii() {
    assertThat(ResourceNameGuard.normalize("주간\u3000보고"))
        .isEqualTo(ResourceNameGuard.normalize("주간 보고"));
    assertThat(ResourceNameGuard.normalize("\u3000Sales"))
        .isEqualTo(ResourceNameGuard.normalize("sales"));
  }

  @Test
  void renamed_ignoresCaseAndSurroundingSpaces() {
    assertThat(ResourceNameGuard.renamed("Sales", "  sales ", NameComparison.ASCII_TRIM)).isFalse();
    assertThat(ResourceNameGuard.renamed("Sales", "Sales2", NameComparison.ASCII_TRIM)).isTrue();
  }

  /**
   * NBSP 가 붙은 옛 이름에서 그 공백만 지운 저장은 개명으로 본다(WD-1085 리뷰) — SQL lower(trim()) 은 NBSP 를 걷지 않아 두 이름을 다르게
   * 보므로, 개명으로 판정해 중복 검사를 돌려야 다른 행의 「Sales」와 겹치는 저장을 막는다.
   */
  @Test
  void renamed_treatsNbspRemovalAsRename() {
    assertThat(ResourceNameGuard.renamed("Sales\u00a0", "Sales", NameComparison.ASCII_TRIM))
        .isTrue();
    assertThat(ResourceNameGuard.renamed("\u3000Sales", "Sales", NameComparison.ASCII_TRIM))
        .isTrue();
  }

  /** WD-1108: 유니코드 비교 기준(스마트 작업 SQL)에서는 전각 공백·NBSP 제거가 개명이 아니다. */
  @Test
  void renamed_unicodeTrim_ignoresUnicodeWhitespaceAndCase() {
    NameComparison u = NameComparison.UNICODE_TRIM;
    assertThat(ResourceNameGuard.renamed("\u3000Sales", "Sales", u)).isFalse();
    assertThat(ResourceNameGuard.renamed("Sales\u00a0", "sales", u)).isFalse();
    assertThat(ResourceNameGuard.renamed("Sales", "Sales2", u)).isTrue();
    // 안쪽 유니코드 공백만 다른 이름은 PG SQL(translate) 과 같게 같은 이름이다(WD-1108).
    assertThat(ResourceNameGuard.renamed("A\u3000B", "A B", u)).isFalse();
  }

  /** WD-1108: ASCII 기준은 기존 동작 그대로 — 전각 공백 제거는 개명이다. */
  @Test
  void renamed_asciiTrim_keepsExistingBehavior() {
    NameComparison a = NameComparison.ASCII_TRIM;
    assertThat(ResourceNameGuard.renamed("\u3000Sales", "Sales", a)).isTrue();
    assertThat(ResourceNameGuard.renamed("Sales", "  sales ", a)).isFalse();
  }

  /** WD-1108: null 규칙은 두 기준 모두 같다. */
  @Test
  void renamed_nullRules_sameForBothComparisons() {
    for (NameComparison c : NameComparison.values()) {
      assertThat(ResourceNameGuard.renamed(null, null, c)).isFalse();
      assertThat(ResourceNameGuard.renamed(null, "a", c)).isTrue();
      assertThat(ResourceNameGuard.renamed("a", null, c)).isTrue();
    }
  }

  /** 이름 변경 판정 뒤 중복 검사를 호출하는지 — taken 호출 횟수로 검사 여부를 본다(ASCII_TRIM 5인자 경로). */
  private static int checks(String before, String newName, boolean taken) {
    return checks(before, newName, null, taken);
  }

  /** 비교 기준을 지정하는 6인자 경로(WD-1108) — comparison 이 null 이면 5인자 오버로드를 쓴다. */
  private static int checks(
      String before, String newName, NameComparison comparison, boolean taken) {
    ResourceNameGuard guard = new ResourceNameGuard(mock(PlatformLocks.class));
    AtomicInteger calls = new AtomicInteger();
    TenantContext.runScoped(
        1L,
        () ->
            invoke(
                guard,
                before,
                newName,
                comparison,
                () -> {
                  calls.incrementAndGet();
                  return taken;
                }));
    return calls.get();
  }

  /** comparison 이 null 이면 5인자(기본 ASCII_TRIM) 오버로드를, 아니면 6인자 오버로드를 호출한다(WD-1108). */
  private static void invoke(
      ResourceNameGuard guard,
      String before,
      String newName,
      NameComparison comparison,
      BooleanSupplier taken) {
    if (comparison == null) {
      guard.requireUnusedOnRename(ResourceKind.CHART, 7L, before, newName, taken);
    } else {
      guard.requireUnusedOnRename(ResourceKind.CHART, 7L, before, newName, comparison, taken);
    }
  }

  /** WD-1108: 6인자 오버로드 — 전각 공백만 제거한 저장은 UNICODE_TRIM 이면 검사 생략, ASCII_TRIM 이면 검사(중복이면 예외). */
  @Test
  void requireUnusedOnRename_comparisonDecidesWhetherCheckRuns() {
    assertThat(checks("\u3000Sales", "Sales", NameComparison.UNICODE_TRIM, true)).isZero();
    assertThatThrownBy(() -> checks("\u3000Sales", "Sales", NameComparison.ASCII_TRIM, true))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThat(checks("\u3000Sales", "Sales", NameComparison.ASCII_TRIM, false)).isEqualTo(1);
  }

  /** WD-1108: UNICODE_TRIM 은 PG SQL 처럼 U+0020 만 걷는다 — U+001F 등 제어 문자는 이름의 일부라 떼면 개명이다. */
  @Test
  void renamed_unicodeTrim_controlCharIsNotTrimmed() {
    NameComparison u = NameComparison.UNICODE_TRIM;
    assertThat(ResourceNameGuard.renamed("Sales\u001f", "Sales", u)).isTrue();
    assertThat(ResourceNameGuard.renamed("\u001fSales", "Sales", u)).isTrue();
    assertThat(ResourceNameGuard.renamed("\u3000Sales", "Sales", u)).isFalse();
    assertThat(ResourceNameGuard.renamed("Sales ", "sales", u)).isFalse();
  }

  /** WD-1108: 제어 문자만 떼어 낸 개명도 중복 검사가 돌고, 이미 있으면 예외. */
  @Test
  void requireUnusedOnRename_unicodeTrim_controlCharRemovalChecksDuplicate() {
    assertThatThrownBy(() -> checks("Sales\u001f", "Sales", NameComparison.UNICODE_TRIM, true))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** WD-1108: 비교 기준 null 은 조용히 ASCII 로 보지 않고 거부한다. */
  @Test
  void requireUnusedOnRename_nullComparisonRejected() {
    ResourceNameGuard guard = new ResourceNameGuard(mock(PlatformLocks.class));
    assertThatThrownBy(
            () -> guard.requireUnusedOnRename(ResourceKind.CHART, 7L, "a", "b", null, () -> false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void requireUnusedOnRename_nullNewNameSkipsCheck() {
    assertThat(checks("Sales", null, true)).isZero();
  }

  @Test
  void requireUnusedOnRename_sameNameDifferentCaseOrSpaceSkipsCheck() {
    assertThat(checks("Sales", "  sales ", true)).isZero();
  }

  @Test
  void requireUnusedOnRename_realRenameChecksAndThrowsWhenTaken() {
    assertThat(checks("Sales", "Sales2", false)).isEqualTo(1);
    assertThatThrownBy(() -> checks("Sales", "Sales2", true))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void copyName_firstIsPlainSuffix() {
    assertThat(ResourceNameGuard.copyName("X", n -> false)).isEqualTo("X (복사본)");
  }

  @Test
  void copyName_picksNextFreeNumber() {
    Set<String> used = Set.of("X (복사본)", "X (복사본 2)");
    assertThat(ResourceNameGuard.copyName("X", used::contains)).isEqualTo("X (복사본 3)");
  }

  @Test
  void copyName_truncatesOriginalTo200CodePoints() {
    String longName = "가".repeat(200);
    String first = ResourceNameGuard.copyName(longName, n -> false);
    assertThat(first).hasSize(200).startsWith("가".repeat(194)).endsWith(" (복사본)");

    String second = ResourceNameGuard.copyName(longName, first::equals);
    assertThat(second).hasSize(200).endsWith(" (복사본 2)").startsWith("가".repeat(192));
  }

  @Test
  void copyName_countsSupplementaryCharsAsOneCodePoint() {
    String name = "😀".repeat(200);
    String copy = ResourceNameGuard.copyName(name, n -> false);
    assertThat(copy.codePointCount(0, copy.length())).isEqualTo(200);
    assertThat(copy).endsWith(" (복사본)");
  }
}
