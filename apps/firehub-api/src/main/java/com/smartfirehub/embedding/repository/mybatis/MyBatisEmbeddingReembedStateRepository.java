package com.smartfirehub.embedding.repository.mybatis;

import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.reembed.ReembedState;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.global.tenant.TenantContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * EmbeddingReembedStateRepository 의 MyBatis 구현(WD-712) — {@code
 * JooqEmbeddingReembedStateRepository} 와 같은 의미.
 *
 * <p>테넌트 재임베딩 상태·임대(embedding_reembed_state, 테넌트당 1행). 클래스 레벨 {@code @Transactional} 은 jOOQ 구현 그대로다
 * — 잡 스레드는 앰비언트 트랜잭션이 없으므로 호출마다 테넌트 트랜잭션이 열려야 정책이 행을 보여 준다.
 *
 * <p>테넌트: jOOQ 판은 모든 문장에 {@code WHERE tenant_id = ?} 를 명시하지만, 이 구현은 인터페이스가 테넌트를 인자로 받지 않으므로 SQL 에
 * tenant_id 를 쓰지 않는다(D3) — MyBatis 플랫폼 문장은 언제나 정책이 켜진 런타임 계정으로 돌고, PK 가 tenant_id 라 정책이 좁힌 뒤에는 현재
 * 테넌트 행 하나만 남는다. 다만 jOOQ 와 같은 순서·같은 예외를 지키려고 모든 메서드가 먼저 {@code TenantContext.require} 로 테넌트를
 * 확인한다(없으면 {@code MissingTenantScopeException}). 주의: 쓰기는 트랜잭션 시작 때 심은 세션 테넌트에 적용된다 — 트랜잭션 안에서 컨텍스트만
 * 바꿔 부르지 않는다(MyBatisTenantSettingsRepository 와 같은 제약).
 *
 * <p>시각은 DB 시계로 쓰고 {@link java.time.Instant} 로 읽어 UTC {@link OffsetDateTime} 으로 돌려준다 — JVM 기본 시간대와
 * 무관하다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisEmbeddingReembedStateRepository implements EmbeddingReembedStateRepository {

  private static final String WHERE = "임베딩 재임베딩 상태";

  private final EmbeddingReembedStateMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈 — 매퍼 SQL 은 mybatis/ 아래 XML). */
  public MyBatisEmbeddingReembedStateRepository(EmbeddingReembedStateMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 임대가 비었거나 만료됐으면 잡는다(한 문장 조건부 UPSERT — 동시 시도 중 하나만 1행). 판정은 RETURNING 대신 영향 행 수다.
   *
   * <p>중복 키(Tibero 전용 분기): PG 는 ON CONFLICT 가 동시 첫 삽입을 기다렸다가 WHERE 를 다시 평가하므로 예외가 나지 않는다. Tibero
   * MERGE 는 같은 테넌트의 첫 삽입이 두 트랜잭션에서 겹치면 뒤쪽이 PK 색인 잠금을 기다리다가, 앞쪽(승자)이 유효한 임대를 커밋한 <b>뒤에야</b> PK 위반으로
   * 끝난다(PK 는 컨텍스트 DEFAULT 로 채운 tenant_id 라 상대는 같은 테넌트의 동시 획득뿐이다). 그러므로 false 는 PG ON CONFLICT 의
   * 답(0행)과 같고, 다시 시도해도 커밋된 유효 임대를 보고 false 가 될 뿐이라 재시도는 아무것도 더하지 않는다(WD-706 「1회 재시도」 규칙에서 벗어남 —
   * 컨트롤러 Ruling). 다른 무결성 오류는 그대로 올라간다.
   */
  @Override
  public boolean tryAcquire(Duration lease) {
    TenantContext.require(WHERE);
    try {
      return mapper.tryAcquire(lease.toSeconds()) == 1;
    } catch (DuplicateKeyException concurrentFirstInsert) {
      return false;
    }
  }

  /** 배치마다 임대를 늘린다 — 긴 재임베딩이 도중에 임대를 잃지 않게. */
  @Override
  public void renewLease(Duration lease) {
    TenantContext.require(WHERE);
    mapper.renewLease(lease.toSeconds());
  }

  /** 잡이 처리할 공간(모델·차원)을 기록하고 이전 실패 사유를 지운다 — 현황 화면이 "무엇으로 옮기는 중"인지 보여준다. */
  @Override
  public void markRunning(EmbeddingSpace space) {
    TenantContext.require(WHERE);
    mapper.markRunning(space.model(), space.dimension().size());
  }

  /** 완주(옛 차원 정리까지 끝남). */
  @Override
  public void markDone() {
    setStatus("DONE", null);
  }

  /** 도중 설정이 바뀌어 멈춤 — 잡이 스스로 다시 투입한 실행이 새 설정으로 이어받는다. */
  @Override
  public void markSuperseded() {
    setStatus("SUPERSEDED", null);
  }

  /**
   * 실패 사유를 남긴다(설정 화면에 노출). 사유는 {@link TiberoTextLimits#truncateErrorMessage} 로 Tibero 열 바이트 한도 안에
   * 자른다.
   */
  @Override
  public void markFailed(String error) {
    setStatus("FAILED", TiberoTextLimits.truncateErrorMessage(error));
  }

  /** 임대 해제(상태는 그대로). */
  @Override
  public void release() {
    TenantContext.require(WHERE);
    mapper.release();
  }

  /** 지금 재임베딩 잡이 유효한 임대를 쥐고 돌고 있는가(RUNNING + 임대 미만료, DB 시계 기준). */
  @Override
  public boolean hasActiveLease() {
    TenantContext.require(WHERE);
    return mapper.countActiveLease() > 0;
  }

  /** 마지막 잡이 FAILED 로 끝났고 그 뒤로 임베딩 설정이 다시 저장되지 않았는가(인터페이스 javadoc). */
  @Override
  public boolean isFailedSinceLastConfigSave() {
    TenantContext.require(WHERE);
    return mapper.countFailedSinceLastConfigSave() > 0;
  }

  /** 현재 테넌트의 마지막 재임베딩 상태. 한 번도 돌지 않았으면 빈 값. */
  @Override
  public Optional<ReembedState> find() {
    TenantContext.require(WHERE);
    return Optional.ofNullable(mapper.selectState())
        .map(
            r ->
                new ReembedState(
                    r.status(),
                    r.model(),
                    r.dimension(),
                    r.lastError(),
                    r.updatedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(r.updatedAt(), ZoneOffset.UTC)));
  }

  /** 상태·사유만 바꾼다(임대는 그대로 — 해제는 {@link #release()} 가 맡는다). */
  private void setStatus(String status, String error) {
    TenantContext.require(WHERE);
    mapper.setStatus(status, error);
  }
}
