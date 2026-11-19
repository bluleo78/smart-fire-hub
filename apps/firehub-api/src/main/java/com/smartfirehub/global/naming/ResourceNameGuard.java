package com.smartfirehub.global.naming;

import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 테넌트·같은 소유자 안의 자산 이름 중복 방지 공용 가드(WD-824). 쿼리·차트·대시보드·스마트 작업·리포트 양식이 함께 쓴다.
 *
 * <p>유일 인덱스를 두지 않는 이유: 이미 겹친 이름이 운영에 있을 수 있어 마이그레이션이 깨진다. 대신 (종류·테넌트·소유자·정규화 이름) 키로 트랜잭션 범위 advisory
 * 잠금을 잡아 「검사 뒤 저장」을 직렬화한다 — 데이터셋 이름 잠금(WD-540)과 같은 {@link PlatformLocks#xactLock}(PG·Tibero 구현은 구현체가
 * 맡는다)을 쓰므로 DB 별 분기가 필요 없다.
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
   * <p>(WD-824) Java {@code strip()}/{@code toLowerCase(Locale.ROOT)} 는 SQL {@code lower(trim())} 동등 비교의 상위 집합이다 — DB 가 같다고
   * 보는 이름은 항상 같은 잠금 키를 공유하므로 경쟁이 생기지 않는다. 반대로 탭·NBSP 나 C 콜레이션에서의 비ASCII 대소문자처럼 Java 만 같다고
   * 보는 이름은 검사에서 놓칠 뿐 경쟁 조건은 아니다. 잠금 키와 {@link #renamed} 가 이 정규화를 쓴다.
   */
  public static String normalize(String name) {
    return name.strip().toLowerCase(Locale.ROOT);
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
   * 수정 경로용 — 이름이 정규화 비교(공백·대소문자 무시)로 바뀐 경우에만 {@link #requireUnused} 로 중복을 검사한다(WD-824). 예전부터 겹친 행의 설명 등 다른
   * 항목 수정은 막지 않기 위해서다. {@code newName} 이 null(이름 미변경 요청)이면 검사하지 않는다.
   *
   * <p>{@code beforeName} 은 반드시 행 잠금 뒤에 다시 읽은 값이어야 한다: 잠금 전 값은 동시 개명과 겹치면 낡아 개명 여부를 잘못 판정한다.
   *
   * @param taken 같은 소유자에게 같은(정규화) 이름이 이미 있는지(자기 자신 제외) — 잠금을 잡은 뒤에 평가된다
   */
  public void requireUnusedOnRename(
      ResourceKind kind, Long ownerId, String beforeName, String newName, BooleanSupplier taken) {
    if (newName != null && renamed(beforeName, newName)) {
      requireUnused(kind, ownerId, newName, taken);
    }
  }

  /** 정규화 비교로 이름이 바뀌었는지. */
  static boolean renamed(String before, String after) {
    if (before == null || after == null) {
      return before != after;
    }
    return !normalize(before).equals(normalize(after));
  }

  /**
   * 복제 이름 — {@code <이름> (복사본)}, 쓰이면 {@code (복사본 2)}, {@code (복사본 3)} … 전체를 200자(코드 포인트) 안으로 이름 쪽을 잘라 맞춘다.
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
