package com.smartfirehub.settings.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.LikePatternUtils;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * 테넌트 설정 오버라이드 저장소.
 *
 * <p><b>tenant_id 를 인자로 받지 않는다.</b> 값은 {@link TenantContext} 에서 오고, DB 쪽에서는
 * RLS 정책이 같은 GUC 로 한 번 더 검증한다(WITH CHECK). 호출부가 tenant_id 를 넘기게 하면
 * "누가 그 값을 정했는가"라는 질문이 호출부마다 생기고, 잘못된 값이 넘어오면 정책이
 * 거부하기는 하지만 그 실패는 42501 로 나타나 원인을 찾기 어렵다.
 *
 * <p>{@code TenantContext.require()} 를 쓰는 것은 <b>쓰기 경로에서만</b> 옳다 — 읽기 해석기는
 * 컨텍스트 없음을 정상 분기로 다뤄야 하므로(설계서 §4.5) 그 판단은 서비스가 한다. 이 저장소의
 * 읽기 메서드({@link #findValue}, {@link #findByPrefix})는 GUC 를 강제하지 않는다 — 컨텍스트가
 * 없으면 RLS 가 스스로 전 행을 차단하므로 애플리케이션 레벨에서 또 막을 필요가 없고, 막아
 * 버리면 Task 4 가 필요로 하는 "컨텍스트 없음은 정상 분기" 계약이 깨진다.
 *
 * <p>{@code SettingsRepository} 와 같은 스타일(정적 {@code Table}/{@code Field} 이름 상수 + 생성
 * jOOQ 클래스 미사용)을 따른다 — 두 저장소가 같은 패키지에서 다른 스타일을 쓰면 읽는 사람이
 * 매번 "왜 여기만 다르지"를 물어야 한다.
 */
@Repository
@RequiredArgsConstructor
public class TenantSettingsRepository {

  private final DSLContext dsl;

  private static final Table<?> TENANT_SETTINGS = table(name("tenant_settings"));
  private static final Field<Long> TENANT_ID = field(name("tenant_settings", "tenant_id"), Long.class);
  private static final Field<String> KEY = field(name("tenant_settings", "key"), String.class);
  private static final Field<String> VALUE = field(name("tenant_settings", "value"), String.class);
  private static final Field<Long> UPDATED_BY =
      field(name("tenant_settings", "updated_by"), Long.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("tenant_settings", "updated_at"), LocalDateTime.class);

  /** 현재 테넌트가 이 키를 오버라이드했는지. 없으면 빈 값 — 플랫폼 기본값을 쓰라는 뜻이다. */
  public Optional<String> findValue(String key) {
    return dsl.select(VALUE).from(TENANT_SETTINGS).where(KEY.eq(key)).fetchOptional(VALUE);
  }

  /**
   * 키 프리픽스로 시작하는 오버라이드 전부를 {@code key → value} 맵으로 반환한다(예: {@code "ai"} →
   * {@code ai.%}). {@code SettingsRepository.findByPrefix} 와 동일하게 {@code LikePatternUtils.escape}
   * 로 프리픽스 자체에 든 LIKE 특수문자를 이스케이프한다.
   */
  public Map<String, String> findByPrefix(String prefix) {
    Map<String, String> result = new HashMap<>();
    dsl.select(KEY, VALUE)
        .from(TENANT_SETTINGS)
        .where(KEY.like(LikePatternUtils.escape(prefix) + ".%", '\\'))
        .fetch()
        .forEach(r -> result.put(r.get(KEY), r.get(VALUE)));
    return result;
  }

  /**
   * 현재 테넌트의 오버라이드를 세팅(신규 삽입 또는 갱신)한다. PK 는 {@code (tenant_id, key)} 이므로
   * 같은 키를 다시 쓰면 행이 늘지 않고 갱신된다.
   *
   * <p><b>value 는 테이블 제약상 NOT NULL 이다.</b> null 을 그대로 흘리면 PostgreSQL 이 23502 로
   * 거부하는데, 그 SQLSTATE 만으로는 호출자가 "오버라이드를 지우려 했다"인지 "버그로 null 이
   * 새어들었다"인지 구별할 수 없다. 이 저장소는 <b>거부</b>를 택한다 — 오버라이드 제거는 이미
   * {@link #delete} 라는 전용 통로가 있으므로, {@code upsert} 에 null 을 "삭제"의 동의어로 얹으면
   * 통로가 두 개가 되어 호출부마다 어느 쪽을 쓸지 갈린다. 그래서 여기서 즉시
   * {@link NullPointerException} 으로 fail-fast 하여 호출부(서비스 레이어)가 null 을 걸러내고
   * 삭제가 필요하면 {@link #delete} 를 부르도록 강제한다.
   */
  public void upsert(String key, String value, Long userId) {
    Objects.requireNonNull(value, "tenant_settings.value 는 NOT NULL 이다 — 삭제는 delete(key) 를 쓸 것");
    long tenantId = TenantContext.require("tenant_settings 오버라이드 저장");
    LocalDateTime now = LocalDateTime.now();
    dsl.insertInto(TENANT_SETTINGS)
        .set(TENANT_ID, tenantId)
        .set(KEY, key)
        .set(VALUE, value)
        .set(UPDATED_BY, userId)
        .set(UPDATED_AT, now)
        .onConflict(TENANT_ID, KEY)
        .doUpdate()
        .set(VALUE, value)
        .set(UPDATED_BY, userId)
        .set(UPDATED_AT, now)
        .execute();
  }

  /** 현재 테넌트의 오버라이드를 지운다. 반환값은 삭제된 행 수 — 0 이면 오버라이드가 없었다. */
  public int delete(String key) {
    return dsl.deleteFrom(TENANT_SETTINGS).where(KEY.eq(key)).execute();
  }
}
