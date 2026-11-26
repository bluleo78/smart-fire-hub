package com.smartfirehub.global.naming;

import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.validation.TextRules;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 테넌트·같은 소유자 안의 자산 이름 중복 방지 공용 가드(WD-824). 쿼리·차트·대시보드·스마트 작업·리포트 양식이 함께 쓴다.
 *
 * <p>유일 인덱스를 두지 않는 이유: 이미 겹친 이름이 운영에 있을 수 있어 마이그레이션이 깨진다. 대신 (종류·테넌트·소유자·정규화 이름) 키로 트랜잭션 범위
 * advisory 잠금을 잡아 「검사 뒤 저장」을 직렬화한다 — 데이터셋 이름 잠금(WD-540)과 같은 {@link
 * PlatformLocks#xactLock}(PG·Tibero 구현은 구현체가 맡는다)을 쓰므로 DB 별 분기가 필요 없다.
 */
@Component
@RequiredArgsConstructor
public class ResourceNameGuard {

  /** 이름 한도(DB varchar(200)) — 코드 포인트 기준. */
  static final int NAME_MAX_LENGTH = 200;

  private final PlatformLocks locks;

  /**
   * 비교용 정규화 — 앞뒤 공백 제거 + 대소문자 무시. 저장 값은 바꾸지 않는다.
   *
   * <p>(WD-824) Java {@code strip()}/{@code toLowerCase(Locale.ROOT)} 는 SQL {@code lower(trim())}
   * 동등 비교의 상위 집합이다 — DB 가 같다고 보는 이름은 항상 같은 잠금 키를 공유하므로 경쟁이 생기지 않는다. 반대로 탭·NBSP 나 C 콜레이션에서의 비ASCII
   * 대소문자처럼 Java 만 같다고 보는 이름은 검사에서 놓칠 뿐 경쟁 조건은 아니다. 잠금 키 전용이다 — {@link #renamed} 의 비교값은
   * 이보다 좁은 SQL 동등 규칙({@code sqlComparable})을 쓴다(WD-1108: 여기서 쓰면 U+001C–U+001F 까지 걷어 개명 검사를 건너뛴다).
   *
   * <p>(WD-1108) 먼저 유니코드 공백(NBSP·전각 등)을 ASCII 공백으로 바꾼다({@link TextRules#spacesToAscii}). 스마트 작업·API
   * 연결의 중복 SQL 이 이름 전체를 {@code translate} 로 같은 식으로 바꿔 비교하므로, DB 가 같다고 보는 이름({@code "주간 보고"} 와
   * {@code "주간\u3000보고"}, 앞뒤 NBSP 등)이 같은 잠금 키를 공유해야 동시 생성이 직렬화된다. 키가 더 거칠어지면 직렬화만 늘 뿐 안전하다.
   */
  public static String normalize(String name) {
    return TextRules.spacesToAscii(name).strip().toLowerCase(Locale.ROOT);
  }

  /**
   * 이름이 아직 안 쓰였는지 잠금 아래에서 확인한다. 호출자는 반드시 {@code @Transactional} 안이어야 한다(밖이면 잠금이 바로 풀려 아무것도 막지 못한다).
   *
   * @param taken 같은 소유자에게 같은(정규화) 이름이 이미 있는지 — 잠금을 잡은 뒤에 평가된다
   * @throws DuplicateResourceNameException 이미 있으면
   */
  public void requireUnused(ResourceKind kind, Long ownerId, String name, BooleanSupplier taken) {
    lock(kind, ownerId, name);
    if (taken.getAsBoolean()) {
      throw new DuplicateResourceNameException(kind);
    }
  }

  /** 검사 없이 잠금만 잡는다 — 복제처럼 잠금 아래에서 여러 후보를 시험할 때. */
  public void lock(ResourceKind kind, Long ownerId, String name) {
    long tenantId = TenantContext.require("이름 중복 잠금");
    locks.xactLock("name:" + kind + ":" + tenantId + ":" + ownerId + ":" + normalize(name));
  }

  /**
   * 수정 경로용 — 이름이 정규화 비교(공백·대소문자 무시)로 바뀐 경우에만 {@link #requireUnused} 로 중복을 검사한다(WD-824). 예전부터 겹친 행의
   * 설명 등 다른 항목 수정은 막지 않기 위해서다. {@code newName} 이 null(이름 미변경 요청)이면 검사하지 않는다.
   *
   * <p>{@code beforeName} 은 반드시 행 잠금 뒤에 다시 읽은 값이어야 한다: 잠금 전 값은 동시 개명과 겹치면 낡아 개명 여부를 잘못 판정한다.
   *
   * @param taken 같은 소유자에게 같은(정규화) 이름이 이미 있는지(자기 자신 제외) — 잠금을 잡은 뒤에 평가된다
   */
  public void requireUnusedOnRename(
      ResourceKind kind, Long ownerId, String beforeName, String newName, BooleanSupplier taken) {
    requireUnusedOnRename(kind, ownerId, beforeName, newName, NameComparison.ASCII_TRIM, taken);
  }

  /**
   * 개명 판정 비교 기준을 고를 수 있는 오버로드(WD-1108). 자원마다 중복 검사 SQL 의 trim 기준이 달라(ASCII 만 / 유니코드 공백까지) 개명 판정도 그
   * 기준을 따라야 한다. 기준이 어긋나면 개명을 놓치거나(검사 누락) 개명이 아닌데 검사를 돌린다.
   *
   * @param comparison 해당 자원의 중복 검사 SQL 이 쓰는 비교 기준
   */
  public void requireUnusedOnRename(
      ResourceKind kind,
      Long ownerId,
      String beforeName,
      String newName,
      NameComparison comparison,
      BooleanSupplier taken) {
    // (WD-1108) null 기준을 조용히 ASCII 로 보면 PG 규칙이 필요한 자원에서 검사가 샌다 — 거부한다.
    Objects.requireNonNull(comparison, "comparison");
    if (newName != null && renamed(beforeName, newName, comparison)) {
      requireUnused(kind, ownerId, newName, taken);
    }
  }

  /**
   * 이름이 바뀌었는지 — 자원의 중복 검사 SQL 비교 기준과 같게 본다.
   *
   * <p>(WD-1085) ASCII_TRIM: SQL {@code lower(trim(name))} 은 ASCII 공백만 걷는다. 서비스가 저장 전에 유니코드 공백을
   * 걷으므로 {@code "Sales "} 로 저장된 옛 행을 {@code "Sales"} 로 다시 저장하는 일이 생기는데, 잠금 키의 {@link #normalize} 로
   * 판정하면 「개명 아님」이 되어 중복 검사가 생략된다. 그래서 SQL 이 다르게 볼 수 있으면 개명으로 판정한다(검사는 자기 자신을 빼므로 잘못 막지 않는다).
   *
   * <p>(WD-1108) UNICODE_TRIM: PostgreSQL 의 스마트 작업 중복 검사 SQL {@code lower(trim(translate(x)))} 와
   * 같게, 이름 전체의 유니코드 공백을 ASCII 공백으로 바꾼({@link TextRules#spacesToAscii}) 뒤 앞뒤 ASCII 공백(U+0020)만 걷고
   * 소문자로 비교한다. {@code String.strip()} 은 U+001C~U+001F·탭·개행도 걷지만 SQL {@code trim} 은 걷지 않으므로 쓰지 않는다 —
   * 쓰면 {@code "Sales\u001f"} 로 저장된 옛 행을 {@code "Sales"} 로 바꿔도 「개명 아님」이 되어 중복 검사가 생략된다. 전각 공백만 제거한
   * 저장은 개명이 아니고, 안쪽 공백 종류만 다른 이름은 같은 이름이다. (WD-1172) Tibero 도 이름 목록을 읽어 Java 에서 이 UNICODE_TRIM 비교값으로 비교하므로
   * PG 와 같은 기준이다.
   */
  static boolean renamed(String before, String after, NameComparison comparison) {
    if (before == null || after == null) {
      return before != after;
    }
    return !comparisonKey(before, comparison).equals(comparisonKey(after, comparison));
  }

  /**
   * 기준별 비교값(WD-1108) — UNICODE_TRIM 은 유니코드 공백을 ASCII 로 바꾼 뒤 {@link #sqlComparable}, ASCII_TRIM 은 바로
   * {@link #sqlComparable}. 잠금 키 {@link #normalize} 는 의도적 상위 집합이라 비교값으로 쓰지 않는다.
   *
   * <p>(WD-1172) 공개 — Tibero 처럼 SQL 로 유니코드 공백을 못 바꾸는 저장소가 이름 목록을 읽어 Java 에서
   * PG SQL 과 같은 기준으로 비교할 때 이 하나를 쓴다(복사본 금지).
   */
  public static String comparisonKey(String name, NameComparison comparison) {
    if (comparison == NameComparison.UNICODE_TRIM) {
      return sqlComparable(TextRules.spacesToAscii(name));
    }
    return sqlComparable(name);
  }

  /**
   * 저장된 이름 목록에 name 과 UNICODE_TRIM 기준으로 같은 이름이 있는지(WD-1172) — Tibero 처럼 SQL 로 유니코드 공백을 못 바꾸는
   * 저장소가 이름 목록을 읽어 PG SQL 과 같은 기준으로 중복을 볼 때 쓴다(저장소마다 비교 블록을 복사하지 않게). name 이 null 이면
   * false, 목록의 null 이름은 건너뛴다.
   */
  public static boolean containsName(Collection<String> stored, String name) {
    if (name == null) {
      return false;
    }
    String key = comparisonKey(name, NameComparison.UNICODE_TRIM);
    return stored.stream()
        .anyMatch(s -> s != null && comparisonKey(s, NameComparison.UNICODE_TRIM).equals(key));
  }

  /** SQL {@code lower(trim(x))} 와 같은 비교값 — 앞뒤 ASCII 공백(U+0020)만 걷고 소문자로 바꾼다. */
  private static String sqlComparable(String name) {
    int start = 0;
    int end = name.length();
    while (start < end && name.charAt(start) == ' ') {
      start++;
    }
    while (end > start && name.charAt(end - 1) == ' ') {
      end--;
    }
    return name.substring(start, end).toLowerCase(Locale.ROOT);
  }

  /**
   * 복제 이름 — {@code <이름> (복사본)}, 쓰이면 {@code (복사본 2)}, {@code (복사본 3)} … 전체를 200자(코드 포인트) 안으로 이름 쪽을
   * 잘라 맞춘다.
   *
   * @param taken 후보 이름이 이미 쓰이는지
   */
  public static String copyName(String original, Predicate<String> taken) {
    for (int n = 1; ; n++) {
      String suffix = n == 1 ? " (복사본)" : " (복사본 " + n + ")";
      int room = NAME_MAX_LENGTH - suffix.codePointCount(0, suffix.length());
      String base = original;
      if (original.codePointCount(0, original.length()) > room) {
        base = original.substring(0, original.offsetByCodePoints(0, room)).stripTrailing();
      }
      String candidate = base + suffix;
      if (!taken.test(candidate)) {
        return candidate;
      }
    }
  }
}
