package com.smartfirehub.settings.repository;

import java.util.Map;
import java.util.Optional;

/**
 * 테넌트별 설정(tenant_settings 테이블) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqTenantSettingsRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface TenantSettingsRepository {

  /** 현재 테넌트가 이 키를 오버라이드했는지. 없으면 빈 값 — 플랫폼 기본값(AI 키는 코드 기본값)을 쓰라는 뜻이다. */
  Optional<String> findValue(String key);

  /**
   * 키 프리픽스로 시작하는 오버라이드 전부를 {@code key → value} 맵으로 반환한다(예: {@code "ai"} → {@code ai.%}). {@code
   * SettingsRepository.findByPrefix} 와 동일하게 {@code LikePatternUtils.escape} 로 프리픽스 자체에 든 LIKE 특수문자를
   * 이스케이프한다.
   */
  Map<String, String> findByPrefix(String prefix);

  /**
   * 현재 테넌트의 오버라이드를 세팅(신규 삽입 또는 갱신)한다. PK 는 {@code (tenant_id, key)} 이므로 같은 키를 다시 쓰면 행이 늘지 않고 갱신된다.
   *
   * <p><b>value 는 테이블 제약상 NOT NULL 이다.</b> null 을 그대로 흘리면 PostgreSQL 이 23502 로 거부하는데, 그 SQLSTATE
   * 만으로는 호출자가 "오버라이드를 지우려 했다"인지 "버그로 null 이 새어들었다"인지 구별할 수 없다. 이 저장소는 <b>거부</b>를 택한다 — 오버라이드 제거는 이미
   * {@link #delete} 라는 전용 통로가 있으므로, {@code upsert} 에 null 을 "삭제"의 동의어로 얹으면 통로가 두 개가 되어 호출부마다 어느 쪽을
   * 쓸지 갈린다. 그래서 여기서 즉시 {@link NullPointerException} 으로 fail-fast 하여 호출부(서비스 레이어)가 null 을 걸러내고 삭제가
   * 필요하면 {@link #delete} 를 부르도록 강제한다.
   */
  void upsert(String key, String value, Long userId);

  /**
   * 현재 테넌트의 오버라이드를 지운다. 반환값은 삭제된 행 수 — 0 이면 오버라이드가 없었다.
   *
   * <p><b>{@link #findValue}/{@link #findByPrefix} 와 달리 tenant_id 를 SQL 에 명시한다.</b> 읽기는 컨텍스트가
   * 없어도(배경 잡) 정상 분기이므로 RLS 에게만 맡기면 되지만({@link #upsert} 위 문서 참고), 이 메서드는 <b>쓰기</b>다 — {@code
   * SECURITY DEFINER}/테스트 픽스처처럼 RLS 를 우회하는 소유자 권한 {@code DSLContext} 로 호출되면 {@code WHERE key = ?}
   * 만으로는 <b>전 테넌트의 같은 키 행이 전부 삭제된다</b>. {@link #upsert} 가 이미 {@code TenantContext.require} 로 테넌트를
   * 명시하는 것과 대칭을 맞춰, 삭제도 어떤 권한의 커넥션으로 실행되든 자기 테넌트 행만 지우게 한다.
   */
  int delete(String key);

  /**
   * 현재 테넌트의 주어진 키 행을 한 번의 DELETE 로 지운다(묶음 해제용, 예: SMTP 6키 — #712). 반환값은 삭제된 행 수다. {@link #delete} 와
   * 같은 이유로 tenant_id 를 SQL 에 명시한다 — RLS 를 우회하는 커넥션으로 불려도 다른 테넌트의 같은 키 행을 지우지 않는다.
   */
  int deleteAll(java.util.Collection<String> keys);
}
