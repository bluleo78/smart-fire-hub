package com.smartfirehub.settings.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * TenantSettingsRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>테넌트 설정 오버라이드 저장소.
 *
 * <p><b>tenant_id 를 인자로 받지 않는다.</b> 값은 {@link TenantContext} 에서 오고, DB 쪽에서는 RLS 정책이 같은 GUC 로 한 번 더
 * 검증한다(WITH CHECK). 호출부가 tenant_id 를 넘기게 하면 "누가 그 값을 정했는가"라는 질문이 호출부마다 생기고, 잘못된 값이 넘어오면 정책이 거부하기는
 * 하지만 그 실패는 42501 로 나타나 원인을 찾기 어렵다.
 *
 * <p>{@code TenantContext.require()} 를 쓰는 것은 <b>쓰기 경로에서만</b> 옳다 — 읽기 해석기는 컨텍스트 없음을 정상 분기로 다뤄야
 * 하므로(설계서 §4.5) 그 판단은 서비스가 한다. 이 저장소의 읽기 메서드({@link #findValue}, {@link #findByPrefix})는 GUC 를 강제하지
 * 않는다 — 컨텍스트가 없으면 RLS 가 스스로 전 행을 차단하므로 애플리케이션 레벨에서 또 막을 필요가 없고, 막아 버리면 Task 4 가 필요로 하는 "컨텍스트 없음은 정상
 * 분기" 계약이 깨진다.
 *
 * <p>{@code SettingsRepository} 와 같은 스타일(정적 {@code Table}/{@code Field} 이름 상수 + 생성 jOOQ 클래스 미사용)을
 * 따른다 — 두 저장소가 같은 패키지에서 다른 스타일을 쓰면 읽는 사람이 매번 "왜 여기만 다르지"를 물어야 한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 지우지 말 것</b> — {@code BackgroundPathTransactionTest} 가 전수
 * 검사한다. GUC 는 트랜잭션이 열릴 때만 주입되므로 ({@code TenantAwareTransactionManager.doBegin}), 트랜잭션 없이 이 리포지토리를
 * 부르면 조회는 조용히 0행이 되고 삽입은 정책 위반(42501)으로 실패한다.
 *
 * <p><b>다만 이 애노테이션이 사주는 것은 "트랜잭션이 있다"까지다 — "테넌트 컨텍스트가 채워져 있다"가 아니다.</b> {@code doBegin} 은 {@link
 * TenantContext} 가 비어 있으면 GUC 를 아예 심지 않고 조용히 지나간다. 따라서 컨텍스트 없이 부르는 배경 경로는 {@code @Transactional} 이
 * 있어도 <b>여전히 조용한 0행</b>을 얻는다. 이 문장을 이렇게 정정해 두는 이유는, 예전 문구가 "리포지토리에 애노테이션이 있으니 배경 경로도 안전하다"고 읽히게 쓰여
 * 있었기 때문이다 — 컨텍스트를 세우는 책임은 여전히 호출자에게 있다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
@Transactional
public class JooqTenantSettingsRepository implements TenantSettingsRepository {

  private final DSLContext dsl;

  private static final Table<?> TENANT_SETTINGS = table(name("tenant_settings"));
  private static final Field<Long> TENANT_ID =
      field(name("tenant_settings", "tenant_id"), Long.class);
  private static final Field<String> KEY = field(name("tenant_settings", "key"), String.class);
  private static final Field<String> VALUE = field(name("tenant_settings", "value"), String.class);
  private static final Field<Long> UPDATED_BY =
      field(name("tenant_settings", "updated_by"), Long.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("tenant_settings", "updated_at"), LocalDateTime.class);

  @Override
  public Optional<String> findValue(String key) {
    return dsl.select(VALUE).from(TENANT_SETTINGS).where(KEY.eq(key)).fetchOptional(VALUE);
  }

  @Override
  public Map<String, String> findByPrefix(String prefix) {
    // LinkedHashMap + ORDER BY key: 이 맵의 키가 getResolvedByPrefix 응답 뒤에 그대로
    // 덧붙으므로, HashMap 이면 오버라이드 전용 키의 순서가 호출마다 달라진다.
    Map<String, String> result = new LinkedHashMap<>();
    dsl.select(KEY, VALUE)
        .from(TENANT_SETTINGS)
        .where(KEY.like(LikePatternUtils.settingKeyPrefixPattern(prefix), '\\'))
        .orderBy(KEY)
        .fetch()
        .forEach(r -> result.put(r.get(KEY), r.get(VALUE)));
    return result;
  }

  @Override
  public void upsert(String key, String value, Long userId) {
    Objects.requireNonNull(value, "tenant_settings.value 는 NOT NULL 이다 — 삭제는 delete(key) 를 쓸 것");
    long tenantId = TenantContext.require("tenant_settings 오버라이드 저장");
    // 저장 시각은 DB 시계로 남긴다 — 이 값을 DB now() 로 기록된 다른 테이블 시각과 비교하는 소비자가 있다
    // (EmbeddingReembedStateRepository.isFailedSinceLastConfigSave). 앱 시계를 쓰면 앱·DB 시계 차이만큼 판정이
    // 뒤집힌다(WD-48).
    Field<LocalDateTime> now = currentLocalDateTime();
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

  @Override
  public int delete(String key) {
    long tenantId = TenantContext.require("tenant_settings 오버라이드 삭제");
    return dsl.deleteFrom(TENANT_SETTINGS).where(KEY.eq(key)).and(TENANT_ID.eq(tenantId)).execute();
  }

  @Override
  public int deleteAll(java.util.Collection<String> keys) {
    long tenantId = TenantContext.require("tenant_settings 묶음 삭제");
    if (keys.isEmpty()) return 0;
    return dsl.deleteFrom(TENANT_SETTINGS)
        .where(KEY.in(keys))
        .and(TENANT_ID.eq(tenantId))
        .execute();
  }
}
