package com.smartfirehub.pipeline.service;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 증분 책갈피 후보값을 캡처한다.
 *
 * <p>{@code _updated_at} = 트랜잭션 시작 시각({@code now()})이므로, 스텝 실행 <b>직전</b>에 "아직 커밋 안 된 가장
 * 오래된 트랜잭션의 시작 시각" 이하로 책갈피를 잡아야 그 트랜잭션이 나중에 커밋한 행을 다음 실행이 놓치지 않는다.
 * 다른 롤 세션의 {@code xact_start} 는 런타임 롤에 가려지므로 V123 의 SECURITY DEFINER 함수를 쓴다.
 *
 * <p>실행 <b>후</b>에 잡으면 실행 도중 커밋된 행을 영원히 건너뛴다 — 캡처 시점은 이 설계의 핵심 불변식이다.
 */
@Service
@RequiredArgsConstructor
public class IncrementalCursorService {

  private final DSLContext dsl;

  /** 책갈피 후보값을 DB 에서 읽는다. 평가 순서(시각 먼저 → 활성 트랜잭션)는 DB 함수 안에서 고정된다. */
  public OffsetDateTime captureCandidate() {
    // 여기서 LEAST 를 조립하지 않는다 — 두 값을 따로 읽으면 그 사이에 시작한 트랜잭션이 양쪽에서 빠진다.
    return dsl.resultQuery("SELECT public.fh_incremental_cursor_candidate()")
        .fetchOne(0, OffsetDateTime.class);
  }
}
