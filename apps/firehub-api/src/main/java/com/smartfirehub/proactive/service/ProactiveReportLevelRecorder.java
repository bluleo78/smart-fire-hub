package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 스마트 작업 실행(리포트)이 읽은 데이터셋의 최고 등급을 실행 행({@code proactive_job_execution.security_level_id})에 남긴다
 * (WD-198).
 *
 * <p>왜 필요한가: 리포트 전문을 열 때 작성자의 <b>현재</b> 등급과 비교하려면 리포트가 어떤 등급의 데이터로 만들어졌는지 알아야 한다. 리포트 본문을
 * 쓰는 것은 ai-agent 이고, 데이터는 ai-agent 가 MCP 도구로 api 를 불러 읽는다. 그래서 api 의 데이터셋 읽기 허용 지점({@code
 * DatasetAccessGuard})이 허용할 때마다 이 기록기에 등급을 알린다.
 *
 * <p>읽기를 실행에 묶는 두 경로:
 *
 * <ul>
 *   <li><b>ai-agent 대행 요청</b>: ai-agent 가 스마트 작업 실행 중의 api 호출에 {@code X-Proactive-Execution-Id} 를 싣는다.
 *       {@code JwtAuthenticationFilter} 가 내부 토큰 대행 인증이 성공한 요청에만 {@link #markRequest} 로 표시하고, 요청 동안 읽은
 *       등급 id 를 요청 속성에 모았다가 요청 끝({@link #flushRequest})에 한 번 기록한다. 요청 중에 바로 쓰지 않는 이유: 읽기 경로 대부분이 읽기
 *       전용 트랜잭션 안이라 UPDATE 를 할 수 없고, 같은 스레드에서 커넥션을 하나 더 잡으면 풀 교착 위험이 있다(SecurityAuditRecorder 클래스
 *       주석과 같은 원칙). 요청 끝에는 서비스 트랜잭션이 모두 끝나 커넥션을 쥐고 있지 않다.
 *   <li><b>api 내부 읽기</b>(실행기 스레드의 컨텍스트 수집·이상 탐지 지표 판정): {@link #runInExecution} 의 ThreadLocal 범위 안에서
 *       알린 등급을 그 자리에서 기록한다 — 실행기 스레드는 바깥 트랜잭션이 없다.
 * </ul>
 *
 * <p><b>헤더 위조 방지</b>: 헤더의 실행 id 는 믿지 않는다. 기록 SQL({@link
 * ProactiveJobExecutionRepository#raiseSecurityLevel})이 「그 실행의 작업 작성자 = 이 요청의 대행 사용자」이고 「요청 시작 시점에 진행
 * 중이었던 실행」(지금 RUNNING 이거나 요청 시작 이후에 끝남)일 때만 갱신한다. 조건에 맞지 않으면 아무 일도 하지 않는다(조용히 무시). 기록은 등급을
 * 올리기만 하므로(열람을 더 좁히기만 한다) 무시가 열람을 넓히는 쪽으로 새지 않는다.
 *
 * <p>비교 기준은 security_level.rank 다(데이터셋 열람 판정과 같은 기준) — 「가장 높은 등급」만 남긴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProactiveReportLevelRecorder {

  /** 요청 속성: 헤더로 받은 실행 id(검증 전 값 — 기록 SQL 이 소유·진행 중 여부를 검증한다). */
  static final String EXECUTION_ATTR = ProactiveReportLevelRecorder.class.getName() + ".execution";

  /** 요청 속성: 내부 대행 인증이 확정한 대행 사용자 id. */
  static final String OWNER_ATTR = ProactiveReportLevelRecorder.class.getName() + ".owner";

  /** 요청 속성: 요청 시작 시각(UTC) — 이 이후에 끝난 실행까지만 「진행 중이던 실행」으로 본다. */
  static final String SINCE_ATTR = ProactiveReportLevelRecorder.class.getName() + ".since";

  /** 요청 속성: 이 요청에서 허용된 읽기의 등급 id 모음. */
  static final String LEVELS_ATTR = ProactiveReportLevelRecorder.class.getName() + ".levels";

  /** 실행기 스레드 범위 — api 가 실행 중에 직접 읽는 경로(컨텍스트 수집·지표 판정). */
  private static final ThreadLocal<Target> SCOPED = new ThreadLocal<>();

  /** 기록 대상 실행 + 그 작성자. */
  private record Target(long executionId, long ownerUserId) {}

  private final ProactiveJobExecutionRepository executionRepository;

  /**
   * 내부 토큰 대행 인증이 성공한 요청에 실행 id 를 표시한다. 값이 없거나 양의 정수가 아니면 표시하지 않는다(무시) — 깨진 헤더로 요청을 실패시키지
   * 않는다(기록은 판정이 아니라 부가 정보다).
   */
  public static void markRequest(HttpServletRequest request, String headerValue, long userId) {
    if (headerValue == null || headerValue.isBlank()) {
      return;
    }
    long executionId;
    try {
      executionId = Long.parseLong(headerValue.trim());
    } catch (NumberFormatException e) {
      return;
    }
    if (executionId <= 0 || userId <= 0) {
      return;
    }
    request.setAttribute(EXECUTION_ATTR, executionId);
    request.setAttribute(OWNER_ATTR, userId);
    request.setAttribute(SINCE_ATTR, ProactiveTime.nowUtc());
  }

  /** 데이터셋 읽기 허용 지점이 허용한 등급을 알린다. 실행에 묶이지 않은 읽기(웹·채팅·배경)면 아무 일도 하지 않는다. */
  public void noteRead(LevelPolicy level) {
    if (level != null) {
      noteLevelId(level.id());
    }
  }

  /**
   * 등급 id 로 알린다 — 이전 리포트 본문을 컨텍스트에 싣는 경로처럼 등급 정책 객체 없이 id 만 아는 호출부가 쓴다. null 이면 무시.
   *
   * <p>실행기 범위 안이면 그 자리에서 기록한다(실행기 스레드는 바깥 트랜잭션이 없다). 대행 요청이면 요청 속성에 모으기만 한다.
   */
  public void noteLevelId(Long levelId) {
    if (levelId == null) {
      return;
    }
    Target scoped = SCOPED.get();
    if (scoped != null) {
      raise(scoped.executionId(), scoped.ownerUserId(), levelId, null);
      return;
    }
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs == null
        || attrs.getAttribute(EXECUTION_ATTR, RequestAttributes.SCOPE_REQUEST) == null) {
      return;
    }
    @SuppressWarnings("unchecked")
    Set<Long> levels =
        (Set<Long>) attrs.getAttribute(LEVELS_ATTR, RequestAttributes.SCOPE_REQUEST);
    if (levels == null) {
      levels = new LinkedHashSet<>();
      attrs.setAttribute(LEVELS_ATTR, levels, RequestAttributes.SCOPE_REQUEST);
    }
    levels.add(levelId);
  }

  /** 지금 스마트 작업 실행에 묶인 요청·범위인가 — 묶이지 않았으면 등급 조회 같은 추가 작업을 건너뛰게 한다. */
  public boolean isActive() {
    if (SCOPED.get() != null) {
      return true;
    }
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    return attrs != null
        && attrs.getAttribute(EXECUTION_ATTR, RequestAttributes.SCOPE_REQUEST) != null;
  }

  /**
   * 데이터셋 id 로 알린다 — 판정이 목록 조건(visibleSql 등)이라 허용 지점이 등급을 모르는 경로(문서 청크 검색·검수 항목 목록·지식그래프 읽기)가
   * 결과에 실린 데이터셋 id 를 넘긴다. 실행에 묶이지 않았으면 조회 없이 끝난다.
   */
  public void noteDatasetIds(Collection<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty() || !isActive()) {
      return;
    }
    Set<Long> ids = new LinkedHashSet<>();
    for (Long id : datasetIds) {
      if (id != null) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    executionRepository.findDatasetLevelIds(ids).forEach(this::noteLevelId);
  }

  /**
   * 요청 끝에 모은 등급을 기록한다 — {@code JwtAuthenticationFilter} 가 필터 체인 뒤(서비스 트랜잭션이 모두 끝난 뒤), 테넌트 컨텍스트를
   * 지우기 전에 부른다. 실패해도 요청을 실패시키지 않는다(경고 로그만).
   */
  public void flushRequest(HttpServletRequest request) {
    Object exec = request.getAttribute(EXECUTION_ATTR);
    Object levels = request.getAttribute(LEVELS_ATTR);
    if (!(exec instanceof Long executionId) || !(levels instanceof Set<?> set) || set.isEmpty()) {
      return;
    }
    long owner = (Long) request.getAttribute(OWNER_ATTR);
    LocalDateTime since = (LocalDateTime) request.getAttribute(SINCE_ATTR);
    for (Object id : set) {
      raise(executionId, owner, (Long) id, since);
    }
    request.removeAttribute(LEVELS_ATTR);
  }

  /**
   * 실행기 스레드에서 실행에 묶인 범위로 작업을 돌린다(컨텍스트 수집·지표 판정). 범위 안에서 알린 등급은 즉시 기록된다. 중첩을 허용한다.
   *
   * <p>주의: ThreadLocal 이라 범위 안에서 다른 스레드(공용 풀)로 넘긴 작업은 묶이지 않는다 — 컨텍스트 수집의 대시보드 조회(공용 풀)는 데이터셋 행이
   * 아니라 이름·개수 같은 메타데이터만 읽는다.
   */
  public <T> T runInExecution(long executionId, long ownerUserId, Supplier<T> work) {
    Target prev = SCOPED.get();
    SCOPED.set(new Target(executionId, ownerUserId));
    try {
      return work.get();
    } finally {
      if (prev == null) {
        SCOPED.remove();
      } else {
        SCOPED.set(prev);
      }
    }
  }

  /** 등급을 올린다(작성자·진행 중 조건은 SQL 이 검증). 실패는 경고 로그만 — 기록 실패로 데이터 읽기 응답을 깨뜨리지 않는다. */
  private void raise(long executionId, long ownerUserId, long levelId, LocalDateTime since) {
    try {
      executionRepository.raiseSecurityLevel(executionId, ownerUserId, levelId, since);
    } catch (Exception e) {
      log.warn(
          "스마트 작업 실행 {} 의 리포트 등급 기록 실패(등급 {}): {}", executionId, levelId, e.getMessage());
    }
  }
}
