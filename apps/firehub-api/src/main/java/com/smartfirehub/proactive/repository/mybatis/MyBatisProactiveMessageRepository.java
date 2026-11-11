package com.smartfirehub.proactive.repository.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveMessageRepository 의 MyBatis 구현(WD-487 PoC) — {@code JooqProactiveMessageRepository} 와 같은
 * 의미.
 *
 * <p>{@code @PlatformMyBatisRepository} 로 등록기가 빈으로 등록하며, Tibero 설치본에서만 주입 후보다(그 밖에는 서비스가 jOOQ 구현을
 * 받는다). 요약 가림 규칙은 jOOQ 구현과 같되, 매퍼가 돌려준 평평한 {@link ProactiveMessageRow} 위에서 수행한다.
 */
// 클래스 레벨 @Transactional 이유: V103 으로 tenant_id 가 생겼고 V104 에서 RLS 가 걸린다. 테넌트 값은 트랜잭션-로컬 GUC 라서
// 트랜잭션 없이 도는 발송 경로(@Scheduled 워커, @Async 실행기)에서는 INSERT 가 NOT NULL 위반으로 깨진다. 전파 REQUIRED 라
// 컨트롤러 경로의 동작은 불변이다. 지우지 말 것.
@PlatformMyBatisRepository
@Transactional
public class MyBatisProactiveMessageRepository implements ProactiveMessageRepository {

  private final ProactiveMessageMapper mapper;
  private final ObjectMapper objectMapper;

  /** 수신자의 현재 보안 등급(WD-198) — 리포트 등급이 그보다 높은 메시지는 요약을 비워 돌려준다. */
  private final ClearanceResolver clearanceResolver;

  public MyBatisProactiveMessageRepository(
      ProactiveMessageMapper mapper,
      ObjectMapper objectMapper,
      ClearanceResolver clearanceResolver) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.clearanceResolver = clearanceResolver;
  }

  @Override
  public Long create(
      Long userId,
      Long executionId,
      String title,
      Map<String, Object> content,
      String messageType) {
    try {
      String contentJson = content != null ? objectMapper.writeValueAsString(content) : "{}";
      ProactiveMessageInsert insert =
          new ProactiveMessageInsert(
              userId,
              executionId,
              title,
              contentJson,
              messageType != null ? messageType : "REPORT");
      mapper.insert(insert);
      return insert.getId();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // jOOQ 구현과 같은 메시지 — 직렬화 실패만 감싼다(DB 오류는 그대로 전파).
      throw new RuntimeException("Failed to serialize content", e);
    }
  }

  @Override
  public List<ProactiveMessageResponse> findUnreadByUserId(Long userId) {
    return toResponses(mapper.findUnreadByUserId(userId), userId);
  }

  @Override
  public int countUnreadByUserId(Long userId) {
    return mapper.countUnreadByUserId(userId);
  }

  @Override
  public void markAsRead(Long id, Long userId) {
    mapper.markAsRead(id, userId, ProactiveTime.nowUtc());
  }

  @Override
  public void markAllAsRead(Long userId) {
    mapper.markAllAsRead(userId, ProactiveTime.nowUtc());
  }

  @Override
  public List<ProactiveMessageResponse> findByUserId(
      Long userId, int limit, int offset, boolean unreadOnly) {
    return toResponses(mapper.findByUserId(userId, limit, offset, unreadOnly), userId);
  }

  /**
   * 행 → 응답 + 리포트 등급 가림(WD-198). 연결 리포트의 등급이 수신자의 현재 최대 등급보다 높으면 요약을 비우고 리포트 열기도 막는다. 자격 계산은 등급 있는
   * 행이 하나라도 있을 때만 한 번 한다(jOOQ 구현과 같은 규칙).
   */
  private List<ProactiveMessageResponse> toResponses(List<ProactiveMessageRow> rows, Long userId) {
    Integer recipientRank = null;
    if (rows.stream().anyMatch(r -> r.reportLevelRank() != null)) {
      recipientRank = clearanceResolver.resolve(userId).rank();
    }
    List<ProactiveMessageResponse> out = new ArrayList<>(rows.size());
    for (ProactiveMessageRow r : rows) {
      Integer reportRank = r.reportLevelRank();
      boolean blocked = reportRank != null && recipientRank < reportRank;
      out.add(toResponse(r, blocked));
    }
    return out;
  }

  private ProactiveMessageResponse toResponse(ProactiveMessageRow r, boolean levelBlocked) {
    try {
      Map<String, Object> content =
          r.contentJson() != null
              ? objectMapper.readValue(r.contentJson(), new TypeReference<>() {})
              : Map.of();
      if (levelBlocked) {
        // 요약(리포트 본문 일부)만 지운다 — 제목·작업 이름·시각은 남겨 「등급 때문에 볼 수 없는 알림」임을 알 수 있게 한다.
        Map<String, Object> masked = new HashMap<>(content);
        masked.remove("summary");
        masked.remove("sections");
        masked.remove("rawText");
        content = masked;
      }
      return new ProactiveMessageResponse(
          r.id(),
          r.userId(),
          r.jobId(),
          r.executionId(),
          r.title(),
          content,
          r.messageType(),
          r.read(),
          r.readAt(),
          r.jobName(),
          r.createdAt(),
          // WD-162: 수신자 = 작업 주인일 때만 true
          r.canViewReport() == 1 && !levelBlocked,
          levelBlocked);
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize content", e);
    }
  }
}
