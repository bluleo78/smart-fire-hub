package com.smartfirehub.global.naming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** WD-824 — 이름 정규화·변경 판정·복제 이름 규칙(순수 로직). */
class ResourceNameGuardTest {

  @Test
  void normalize_stripsAndLowercases() {
    assertThat(ResourceNameGuard.normalize("  Sales ")).isEqualTo("sales");
  }

  @Test
  void renamed_ignoresCaseAndSurroundingSpaces() {
    assertThat(ResourceNameGuard.renamed("Sales", "  sales ")).isFalse();
    assertThat(ResourceNameGuard.renamed("Sales", "Sales2")).isTrue();
  }

  /** 이름 변경 판정 뒤 중복 검사를 호출하는지 — taken 호출 횟수로 검사 여부를 본다. */
  private static int checks(String before, String newName, boolean taken) {
    ResourceNameGuard guard = new ResourceNameGuard(mock(PlatformLocks.class));
    AtomicInteger calls = new AtomicInteger();
    TenantContext.runScoped(
        1L,
        () ->
            guard.requireUnusedOnRename(
                ResourceKind.CHART,
                7L,
                before,
                newName,
                () -> {
                  calls.incrementAndGet();
                  return taken;
                }));
    return calls.get();
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
