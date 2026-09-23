package com.smartfirehub.ai.service;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import com.smartfirehub.ai.exception.AiSessionNotFoundException;
import com.smartfirehub.ai.repository.AiSessionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AiSessionService {

  private final AiSessionRepository aiSessionRepository;

  /**
   * 사용자 AI 세션 목록을 페이지네이션으로 조회한다.
   *
   * <p>기존에는 전체 목록을 반환하여 세션이 많을 때(471건 등) 성능 저하가 발생했다. page·size 파라미터를 레포지토리에 전달하여 LIMIT/OFFSET
   * 슬라이싱을 적용한다.
   *
   * @param userId 조회할 사용자 ID
   * @param page 0-based 페이지 번호 (기본값 0)
   * @param size 페이지당 항목 수 (기본값 20)
   * @return 페이지네이션된 세션 목록
   */
  public List<AiSessionResponse> getSessions(Long userId, int page, int size) {
    return aiSessionRepository.findByUserId(userId, page, size);
  }

  public Optional<AiSessionResponse> getSessionByContext(
      Long userId, String contextType, Long contextResourceId) {
    return aiSessionRepository.findByUserIdAndContext(userId, contextType, contextResourceId);
  }

  @Transactional
  public AiSessionResponse createSession(Long userId, CreateAiSessionRequest request) {
    return aiSessionRepository.create(userId, request);
  }

  @Transactional
  public void updateSessionTitle(Long userId, Long sessionId, String title) {
    AiSessionResponse session =
        aiSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new AiSessionNotFoundException(sessionId));
    if (!session.userId().equals(userId)) {
      throw new AccessDeniedException("AI 세션에 대한 권한이 없습니다: " + sessionId);
    }
    aiSessionRepository.updateTitle(sessionId, title);
  }

  public void verifySessionOwnership(Long userId, String sessionId) {
    aiSessionRepository
        .findByUserIdAndSessionId(userId, sessionId)
        .orElseThrow(() -> new AccessDeniedException("Access denied for AI session: " + sessionId));
  }

  /**
   * 채팅으로 이어 쓰려는 세션이 다른 사용자에게 기록된 것이면 거절한다(이슈 #714).
   *
   * <p>{@link #verifySessionOwnership} 처럼 "본인 기록이 있어야 통과" 로 하지 않는 이유: 웹은 첫
   * 응답의 init 에서 세션을 기록하고 그 실패를 무시한다. 기록이 빠진 본인 세션까지 막으면 그 대화는
   * 매 턴 403 으로 영영 이어 쓸 수 없게 된다. 막아야 할 것은 남의 기록이 있는 세션이므로 그것만
   * 거절한다.
   */
  public void verifyNotOthersSession(Long userId, String sessionId) {
    aiSessionRepository
        .findOwnerUserId(sessionId)
        .filter(owner -> !owner.equals(userId))
        .ifPresent(
            owner -> {
              throw new AccessDeniedException("Access denied for AI session: " + sessionId);
            });
  }

  @Transactional
  public void deleteSession(Long userId, Long sessionId) {
    AiSessionResponse session =
        aiSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new AiSessionNotFoundException(sessionId));
    if (!session.userId().equals(userId)) {
      throw new AccessDeniedException("AI 세션에 대한 권한이 없습니다: " + sessionId);
    }
    aiSessionRepository.delete(sessionId);
  }
}
