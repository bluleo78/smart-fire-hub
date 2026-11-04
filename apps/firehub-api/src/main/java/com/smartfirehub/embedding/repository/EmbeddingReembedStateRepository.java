package com.smartfirehub.embedding.repository;

import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.reembed.ReembedState;
import java.time.Duration;
import java.util.Optional;

/**
 * 테넌트 재임베딩 상태·임대(embedding_reembed_state, 테넌트당 1행). 모든 문장이 {@code WHERE tenant_id = ?} 를 명시한다(RLS 에
 * 더해 — 조건 없는 DML 금지 규율). 잡 스레드는 앰비언트 트랜잭션이 없으므로 클래스 레벨 {@code @Transactional} 이 호출마다 GUC 를 세운다.
 *
 * <p>구현은 {@code JooqEmbeddingReembedStateRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface EmbeddingReembedStateRepository {

  /**
   * 임대가 비었거나 만료됐으면 잡는다(한 문장 조건부 UPSERT — 동시 시도 중 하나만 1행을 돌려받는다,
   * SearchIndexStateRepository.tryAcquireLease 와 같은 방식).
   */
  boolean tryAcquire(Duration lease);

  /** 배치마다 임대를 늘린다 — 긴 재임베딩이 도중에 임대를 잃지 않게. */
  void renewLease(Duration lease);

  /** 잡이 처리할 공간(모델·차원)을 기록하고 이전 실패 사유를 지운다 — 현황 화면이 "무엇으로 옮기는 중"인지 보여준다. */
  void markRunning(EmbeddingSpace space);

  /** 완주(옛 차원 정리까지 끝남). */
  void markDone();

  /** 도중 설정이 바뀌어 멈춤 — 잡이 스스로 다시 투입한 실행이 새 설정으로 이어받는다. */
  void markSuperseded();

  /** 실패 사유를 남긴다(설정 화면에 노출). */
  void markFailed(String error);

  /** 임대 해제(상태는 그대로). */
  void release();

  /**
   * 지금 재임베딩 잡이 유효한 임대를 쥐고 돌고 있는가(RUNNING + 임대 미만료). 백로그 스윕이 "이미 도는 잡"에 새 잡을 겹쳐 투입하지 않으려고 본다. 만료 판정은
   * {@link #tryAcquire} 와 같이 DB {@code now()} 기준이다(앱 시계 어긋남 배제).
   */
  boolean hasActiveLease();

  /**
   * 마지막 잡이 FAILED 로 끝났고 그 뒤로 임베딩 설정({@code tenant_settings.embedding.config})이 다시 저장되지 않았는가. 백로그
   * 스윕이 결정적 실패(잘못된 키·가드 거부 주소)를 5분마다 되풀이하지 않게 하는 판정이다 — 설정을 고쳐 저장하면(updated_at 이 실패 기록 뒤) 거짓이 되어 다시
   * 투입된다. 설정 행이 없으면 참(미설정은 스윕이 먼저 걸러 낸다).
   *
   * <p>시각 비교: 설정 행 updated_at 은 TIMESTAMP(앱이 LocalDateTime.now() 로 씀), 상태 행은 TIMESTAMPTZ(DB now()).
   * PostgreSQL 은 세션 TimeZone 으로 TIMESTAMP 를 TIMESTAMPTZ 로 올려 비교하고, pgjdbc 는 세션 TimeZone 을 JVM 기본
   * 시간대로 맞추므로 두 값은 같은 기준이다. 둘 다 WHERE tenant_id 를 명시한다.
   */
  boolean isFailedSinceLastConfigSave();

  /** 현재 테넌트의 마지막 재임베딩 상태. 한 번도 돌지 않았으면 빈 값. */
  Optional<ReembedState> find();
}
