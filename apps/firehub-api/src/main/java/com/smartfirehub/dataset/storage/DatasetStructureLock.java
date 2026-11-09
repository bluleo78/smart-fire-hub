package com.smartfirehub.dataset.storage;

import com.smartfirehub.global.tenant.DataSchema;
import java.time.Duration;

/**
 * 데이터셋 테이블 「구조 잠금」의 키·문구·대기 시간 단일 출처(WD-670).
 *
 * <p><b>왜 필요한가.</b> WD-666 의 데이터셋 행 잠금(KEY SHARE ↔ FOR UPDATE)은 플랫폼 트랜잭션 안에서 쓰는 경로만 막는다. 파이프라인 SQL
 * 스텝(앱 안 실행 — 테넌트 파이프라인 풀, 실행기 — firehub-executor)과 실행기의 PG 적재(Python·API_CALL APPEND)는 플랫폼 트랜잭션 없이
 * 데이터 저장소 연결에서 바로 쓴다. 그 쓰기에 플랫폼 행 잠금을 쥐게 하면 쓰는 동안 플랫폼 풀 연결을 하나씩 붙잡아 풀이 마른다(이전 시도 A 가 되돌려진 이유). 그래서
 * 이 쓰기들은 <b>자기 데이터 저장소 트랜잭션의 첫 문장</b>으로 이 키의 공유 advisory 잠금({@code pg_advisory_xact_lock_shared}) 을
 * 잡고, 구조 변경(필드 추가·변경·삭제, truncate·replace, 데이터셋 삭제, TEMP 재생성)은 데이터셋 행 FOR UPDATE 바로 뒤에 같은 키의 배타 잠금을
 * 시도한다({@link DataTableStore#lockStructureExclusive}).
 *
 * <p><b>키.</b> {@code "ds-structure:" + DataSchema.qualify(테이블)} 의 {@code hashtextextended(키, 0)}
 * 이다. 접두사가 있어 출력 직렬화 잠금(#731 — 접두사 없는 한정 이름)·테넌트 동기화 잠금과 다른 키다. advisory 키 공간은 DB 전체가 공유하므로 테넌트
 * 스키마를 포함한다. firehub-executor 도 같은 문자열을 받아(api 가 만들어 보낸다) 같은 해시로 잠근다.
 *
 * <p><b>대기.</b> 구조 변경은 무한정 기다리지 않는다(결정 A) — 긴 적재(API 수집 등)가 공유 잠금을 오래 쥘 수 있어, 사용자 요청이 그동안 플랫폼 연결과
 * 데이터셋 행 잠금을 쥔 채 멈추면 안 된다. {@link #EXCLUSIVE_WAIT} 동안 시도하고 못 잡으면 409({@link #BUSY_MESSAGE})로 끝낸다.
 */
public final class DatasetStructureLock {

  private DatasetStructureLock() {}

  /** 구조 잠금 키 접두사 — 출력 직렬화 잠금(#731)·테넌트 동기화 잠금 키와 겹치지 않게 한다. */
  public static final String KEY_PREFIX = "ds-structure:";

  /** 구조 변경이 배타 잠금을 시도하는 최대 시간(결정 A). */
  public static final Duration EXCLUSIVE_WAIT = Duration.ofSeconds(5);

  /** 구조 변경이 시간 안에 배타 잠금을 못 잡았을 때의 409 문구 — 파이프라인이 그 데이터셋에 쓰는 중이다. */
  public static final String BUSY_MESSAGE = "파이프라인이 이 데이터셋에 쓰는 중입니다. 잠시 뒤 다시 시도해 주세요";

  /** 데이터 저장소 공유 잠금 문장 — 쓰기 트랜잭션의 첫 문장으로 실행한다(커밋·롤백 때 풀린다). 실행기(sql_executor.py)도 같은 문장이다. */
  public static final String SHARED_LOCK_SQL =
      "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))";

  /**
   * 현재 테넌트에서 테이블의 구조 잠금 키 — {@code ds-structure:<스키마>."<테이블>"}. 테넌트 문맥 안에서 부른다({@link
   * DataSchema#qualify}).
   */
  public static String keyFor(String tableName) {
    return KEY_PREFIX + DataSchema.qualify(tableName);
  }

  /** 키에서 한정 이름({@code <스키마>."<테이블>"})만 떼어 낸다 — 잠금 뒤 테이블 존재 확인({@code to_regclass})에 쓴다. */
  public static String qualifiedNameOf(String key) {
    if (key == null || !key.startsWith(KEY_PREFIX)) {
      throw new IllegalArgumentException("구조 잠금 키가 아닙니다: " + key);
    }
    return key.substring(KEY_PREFIX.length());
  }
}
