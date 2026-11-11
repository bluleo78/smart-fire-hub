package com.smartfirehub.ai.repository.mybatis;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import com.smartfirehub.ai.repository.AiSessionRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * AiSessionRepository 의 MyBatis 구현(WD-712) — {@code JooqAiSessionRepository} 와 같은 의미. SQL 은 {@code
 * mybatis/ai/AiSessionMapper.xml}.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현에서 그대로 옮겼다 — ai_session 은 RLS 대상이라 테넌트 값(PG GUC·Tibero
 * 컨텍스트)이 트랜잭션 안에서만 심긴다. 트랜잭션 없이 도는 경로에서는 INSERT 가 NOT NULL 위반이 되고 조회는 조용히 0행이 된다. Slack inbound 는
 * 호출자({@code SlackInboundService.dispatch})가 {@code TenantContext.runScoped} 로 테넌트를 세워 도착한다. 전파는
 * REQUIRED 라 컨트롤러 경로의 동작은 불변이다.
 *
 * <p>제목은 DB 열(255자)보다 길어도 세션 행 INSERT 가 실패하지 않게 저장 직전에 항상 255자로 자른다(WD-58). Tibero 는 빈 문자열을 NULL 로
 * 저장하므로 제목 {@code ""} 은 null 로 돌아온다(emptyStringIsNull). 길이 무제한 열은 없다(제목 255·세션 ID 255·컨텍스트 50) —
 * Tibero VARCHAR2(65532) 바이트 상한과 무관하다.
 *
 * <p>시각 기준: created_at·updated_at 은 시간대 없는 TIMESTAMP 라 JVM 시간대 벽시계({@link LocalDateTime#now()})를
 * 바인드해 쓰고 그대로 읽는다 — jOOQ 판의 {@code currentLocalDateTime()}(PG LOCALTIMESTAMP, 세션 시간대 = 접속 시 JVM
 * 시간대)과 같은 기준이다. Tibero DB 시계(dbNow)는 UTC 벽시계라 쓰지 않는다(KST JVM 에서 9시간 어긋난다).
 *
 * <p>유일 제약 위반(Slack 스레드 중복)은 두 DB 모두 {@code DuplicateKeyException} 으로 올라와 호출자(SlackInboundService)가
 * 그대로 잡는다 — 재시도하지 않는다(MERGE 없음).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisAiSessionRepository implements AiSessionRepository {

  /** ai_session.title 컬럼 상한(VARCHAR(255)) — jOOQ 구현과 같은 값(WD-58). */
  private static final int TITLE_MAX_LENGTH = 255;

  private final AiSessionMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈 — 매퍼 SQL 은 mybatis/ 아래 XML). */
  public MyBatisAiSessionRepository(AiSessionMapper mapper) {
    this.mapper = mapper;
  }

  /** 사용자의 세션 한 페이지. page 는 0 부터이고 offset 은 long 으로 계산한다(오버플로 방지). */
  @Override
  public List<AiSessionResponse> findByUserId(Long userId, int page, int size) {
    return mapper.selectByUser(userId, size, (long) page * size);
  }

  /** id 로 세션 한 건(WEB 형태 — 채널 "WEB", Slack 필드 null). 없거나 다른 테넌트 행이면 빈 Optional. */
  @Override
  public Optional<AiSessionResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  /** 사용자·컨텍스트(종류·리소스 id)가 모두 일치하는 세션. 없으면 빈 Optional. */
  @Override
  public Optional<AiSessionResponse> findByUserIdAndContext(
      Long userId, String contextType, Long contextResourceId) {
    return Optional.ofNullable(
        mapper.selectByUserAndContext(userId, contextType, contextResourceId));
  }

  /** 사용자·외부 세션 ID 가 모두 일치하는 세션. 남의 세션은 없는 것처럼 비어 돌아온다. */
  @Override
  public Optional<AiSessionResponse> findByUserIdAndSessionId(Long userId, String sessionId) {
    return Optional.ofNullable(mapper.selectByUserAndSessionId(userId, sessionId));
  }

  /** 이 세션이 다른 사용자에게 기록돼 있는지. RLS 로 현재 테넌트 안에서만 본다. */
  @Override
  public boolean existsForOtherUser(Long userId, String sessionId) {
    return mapper.countForOtherUser(userId, sessionId) > 0;
  }

  /** WEB 세션을 만들고(제목 255자 자르기, 시각은 JVM 벽시계) 다시 읽은 행을 돌려준다. */
  @Override
  public AiSessionResponse create(Long userId, CreateAiSessionRequest request) {
    AiSessionInsert row =
        new AiSessionInsert(
            userId,
            request.sessionId(),
            request.contextType(),
            request.contextResourceId(),
            TiberoTextLimits.truncate(request.title(), TITLE_MAX_LENGTH),
            null,
            null,
            null,
            LocalDateTime.now());
    mapper.insertWeb(row);
    return findById(row.getId()).orElseThrow();
  }

  /** 제목을 바꾸고(255자 자르기) 수정 시각을 JVM 벽시계로 올린다. */
  @Override
  public void updateTitle(Long id, String title) {
    mapper.updateTitle(id, TiberoTextLimits.truncate(title, TITLE_MAX_LENGTH), LocalDateTime.now());
  }

  /** id 로 세션 한 행을 지운다. 없는 id 면 아무 일도 하지 않는다. */
  @Override
  public void delete(Long id) {
    mapper.deleteById(id);
  }

  /** SLACK 스레드 기준 세션 조회. 채널·Slack 필드까지 실제 값으로 채운다. */
  @Override
  public Optional<AiSessionResponse> findBySlackContext(
      String teamId, String channelId, String threadTs) {
    return Optional.ofNullable(mapper.selectBySlackContext(teamId, channelId, threadTs));
  }

  /** 새 SLACK 세션을 넣고 id 를 돌려준다. 같은 (team, channel, thread) 이면 DuplicateKeyException. */
  @Override
  public long createSlackSession(
      long userId,
      String aiAgentSessionId,
      String teamId,
      String channelId,
      String threadTs,
      String title) {
    AiSessionInsert row =
        new AiSessionInsert(
            userId,
            aiAgentSessionId,
            null,
            null,
            TiberoTextLimits.truncate(title, TITLE_MAX_LENGTH),
            teamId,
            channelId,
            threadTs,
            LocalDateTime.now());
    mapper.insertSlack(row);
    return row.getId();
  }
}
