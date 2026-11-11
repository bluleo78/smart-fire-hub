package com.smartfirehub.settings.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * TenantSettingsRepository 의 MyBatis 구현(WD-706) — {@code JooqTenantSettingsRepository} 와 같은 의미.
 *
 * <p><b>클래스 레벨 {@code @Transactional}</b> 은 jOOQ 구현 그대로다 — 테넌트 GUC·컨텍스트는 트랜잭션이 열릴 때만 심긴다. 다만 이것이
 * 사주는 것은 "트랜잭션이 있다"까지이고, 컨텍스트 없이 부르면 읽기는 정책이 막아 조용히 빈 값이다(읽기 해석기의 정상 분기 — 여기서 막지 않는다).
 *
 * <p>쓰기는 {@code TenantContext.require} 로 테넌트를 먼저 확인한다(jOOQ 와 같은 순서·같은 예외: upsert 는 null 값 거부가 먼저,
 * deleteAll 은 빈 목록 확인보다 테넌트 확인이 먼저). jOOQ 구현과 달리 tenant_id 는 SQL 에 쓰지 않는다 — MyBatis 플랫폼 문장은 언제나 정책이
 * 켜진 런타임 계정으로 돌므로(PG app_tenant, Tibero 런타임 계정) 정책이 현재 테넌트 행으로 좁히고, 전역 제약(테넌트를 인자로 받지 않는 문장에는
 * tenant_id 를 쓰지 않는다)을 따른다. jOOQ 쪽이 술어를 명시한 이유(소유자 권한 커넥션으로 불리는 경우)는 이 구현의 배선에 없다.
 *
 * <p><b>주의(D2)</b>: 쓰기는 {@code TenantContext.require} 로 확인한 테넌트가 아니라 <b>트랜잭션 시작 때 심은 세션 테넌트</b>(PG
 * GUC, Tibero 컨텍스트)에 적용된다. 그래서 트랜잭션 안에서 {@code TenantContext.runScoped} 등으로 다른 테넌트로 바꿔 부르면 확인은 바뀐
 * 테넌트로 통과하지만 행은 처음 테넌트에 쓰이거나(DEFAULT) 정책에 막힌다 — 테넌트를 바꾸려면 새 트랜잭션을 연다.
 *
 * <p>빈 문자열 값은 변환하지 않는다(controller Ruling) — value 가 NOT NULL 이라 Tibero 는 {@code ""} 저장을 거절한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisTenantSettingsRepository implements TenantSettingsRepository {

  private final TenantSettingsMapper mapper;

  public MyBatisTenantSettingsRepository(TenantSettingsMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<String> findValue(String key) {
    return Optional.ofNullable(mapper.selectValue(key));
  }

  @Override
  public Map<String, String> findByPrefix(String prefix) {
    // LinkedHashMap + ORDER BY key: 응답 뒤에 그대로 덧붙으므로 순서가 호출마다 같아야 한다(jOOQ 구현과 같다).
    Map<String, String> result = new LinkedHashMap<>();
    mapper
        .selectByPattern(LikePatternUtils.settingKeyPrefixPattern(prefix))
        .forEach(e -> result.put(e.key(), e.value()));
    return result;
  }

  /**
   * Tibero 의 MERGE 는 같은 키가 처음으로 동시에 들어오면 한쪽이 PK 위반으로 실패할 수 있다(PG ON CONFLICT 는 그렇지 않다). 문장 단위 실패라
   * 트랜잭션이 살아 있으므로 한 번 다시 시도하면 이미 들어간 행을 갱신한다.
   *
   * <p>이 재시도는 <b>Tibero MERGE 전용</b>이다 — PG 판 문장은 {@code ON CONFLICT DO UPDATE} 라 이 예외를 내지 않아 잡히는
   * 일이 없다(PG 에서 났다면 트랜잭션이 이미 중단돼 재시도도 실패하므로 예외가 그대로 올라간다). 구현이 DB 종류를 몰라도 되게 벤더 분기 없이 둔다.
   */
  @Override
  public void upsert(String key, String value, Long userId) {
    Objects.requireNonNull(value, "tenant_settings.value 는 NOT NULL 이다 — 삭제는 delete(key) 를 쓸 것");
    TenantContext.require("tenant_settings 오버라이드 저장");
    try {
      mapper.upsert(key, value, userId);
    } catch (DuplicateKeyException e) {
      mapper.upsert(key, value, userId);
    }
  }

  @Override
  public int delete(String key) {
    TenantContext.require("tenant_settings 오버라이드 삭제");
    return mapper.deleteByKey(key);
  }

  @Override
  public int deleteAll(Collection<String> keys) {
    TenantContext.require("tenant_settings 묶음 삭제");
    int deleted = 0;
    for (List<String> chunk : InChunks.of(keys)) {
      deleted += mapper.deleteByKeys(chunk);
    }
    return deleted;
  }
}
