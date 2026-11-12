package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.RefLookup;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.RefRow;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TriggerTenantResolver 의 MyBatis 구현(WD-708, 스펙 D2) — {@code JooqTriggerTenantResolver} 와 같은 결과.
 *
 * <ul>
 *   <li>PG: jOOQ 와 같이 SECURITY DEFINER 함수 {@code resolve_trigger_tenant_by_*} 가 (트리거 id, 테넌트 id) 를
 *       한 번에 준다.
 *   <li>Tibero: {@code FH_TENANT.resolve_trigger_tenant_by_*}(V156.1)는 테넌트 id 만 준다 — 새 마이그레이션을 만들지
 *       않고, 얻은 테넌트로 <b>새 트랜잭션</b>(REQUIRES_NEW)을 열어 {@link
 *       PlatformTenantSession#applyToCurrentTransaction} 으로 테넌트를 심은 뒤 같은 CASE 식으로 트리거 id 를 RLS 아래에서
 *       다시 읽는다.
 * </ul>
 *
 * <p>벤더 분기 플래그는 없다 — 해석 문장의 결과 모양이 갈래를 정한다(PG 는 트리거 id 가 채워진 행, Tibero 는 OUT 인자의 테넌트만). 테넌트만 왔을 때
 * 재조회한다.
 *
 * <p>왜 REQUIRES_NEW 인가: 호출자가 트랜잭션 안에 있어도(테스트·향후 경로) 테넌트를 심는 일이 그 트랜잭션에 새지 않게 하려는 것이다. 새 트랜잭션은 자기
 * 커넥션을 받고, 끝날 때 트랜잭션 매니저가 그 커넥션의 테넌트 값을 지운다(호출자 트랜잭션의 커넥션·테넌트는 그대로).
 *
 * <p>클래스·메서드에 {@code @Transactional} 을 붙이지 않는다 — jOOQ 판처럼 테넌트 컨텍스트가 <b>아직 없는</b> 시점에 불린다. 정책 우회 함수
 * 호출에는 트랜잭션이 필요 없고, 재조회 트랜잭션은 이 클래스가 직접 연다.
 *
 * <p>틀렸을 때 비용: 왕복 한 번 더. 두 조회 사이에 트리거가 지워지면 빈 값(없는 토큰과 같은 응답)이다 — 받아들인다.
 */
@PlatformMyBatisRepository
public class MyBatisTriggerTenantResolver implements TriggerTenantResolver {

  private final TriggerTenantResolverMapper mapper;
  private final PlatformTenantSession tenantSession;

  /** 재조회 전용 새 트랜잭션(REQUIRES_NEW) — 플랫폼 트랜잭션 매니저(테넌트 인식) 위. */
  private final TransactionTemplate rereadTx;

  public MyBatisTriggerTenantResolver(
      TriggerTenantResolverMapper mapper,
      PlatformTenantSession tenantSession,
      PlatformTransactionManager transactionManager) {
    this.mapper = mapper;
    this.tenantSession = tenantSession;
    this.rereadTx = new TransactionTemplate(transactionManager);
    this.rereadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Optional<TriggerRef> resolveByApiToken(String tokenHash) {
    return resolve(tokenHash, mapper::resolveByTokenHash, mapper::selectTriggerIdByTokenHash);
  }

  @Override
  public Optional<TriggerRef> resolveByWebhookId(String webhookId) {
    return resolve(webhookId, mapper::resolveByWebhookId, mapper::selectTriggerIdByWebhookId);
  }

  /**
   * 두 해석의 공통부. 식별자가 전역 유니크(V94)라 결과는 0행 또는 1행이다.
   *
   * @param lookup 정책 우회 해석(PG 는 행, Tibero 는 OUT 인자의 테넌트)
   * @param reread 테넌트를 심은 트랜잭션에서 트리거 id 를 다시 읽는 문장(Tibero 전용)
   */
  private Optional<TriggerRef> resolve(
      String identifier, Function<RefLookup, List<RefRow>> lookup, Function<String, Long> reread) {
    if (identifier == null || identifier.isEmpty()) {
      return Optional.empty();
    }
    RefLookup q = new RefLookup(identifier);
    List<RefRow> rows = lookup.apply(q);
    if (rows != null && !rows.isEmpty() && rows.get(0).triggerId() != null) {
      RefRow row = rows.get(0);
      return Optional.of(new TriggerRef(row.triggerId(), row.tenantId()));
    }
    Long tenantId = q.getTenantId();
    if (tenantId == null) {
      return Optional.empty();
    }
    Long triggerId =
        rereadTx.execute(
            status -> {
              tenantSession.applyToCurrentTransaction(tenantId);
              return reread.apply(identifier);
            });
    return triggerId == null ? Optional.empty() : Optional.of(new TriggerRef(triggerId, tenantId));
  }
}
