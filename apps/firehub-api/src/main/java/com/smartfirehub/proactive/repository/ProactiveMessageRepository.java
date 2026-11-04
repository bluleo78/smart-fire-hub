package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import java.util.List;
import java.util.Map;

/**
 * 프로액티브 메시지(사용자 알림함) 저장소.
 *
 * <p>구현은 {@code JooqProactiveMessageRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * proactive_message}, {@code proactive_job_execution}, {@code proactive_job}, {@code
 * security_level}.
 */
public interface ProactiveMessageRepository {

  Long create(
      Long userId, Long executionId, String title, Map<String, Object> content, String messageType);

  List<ProactiveMessageResponse> findUnreadByUserId(Long userId);

  int countUnreadByUserId(Long userId);

  void markAsRead(Long id, Long userId);

  void markAllAsRead(Long userId);

  /**
   * 알림 목록 조회 (#520).
   *
   * <p>{@code unreadOnly=true} 이면 안 읽은 알림만 조회한다 — 안 읽은 알림이 많이 쌓였을 때 "더 보기"로 전체를 스크롤하지 않고도 안 읽은 것만
   * 골라볼 수 있도록 서버 사이드 필터링을 지원한다. 기존 페이지네이션(limit/offset)과 함께 적용된다.
   */
  List<ProactiveMessageResponse> findByUserId(
      Long userId, int limit, int offset, boolean unreadOnly);
}
