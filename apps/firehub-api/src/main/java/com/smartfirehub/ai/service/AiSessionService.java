package com.smartfirehub.ai.service;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import com.smartfirehub.ai.exception.AiSessionNotFoundException;
import com.smartfirehub.ai.repository.AiSessionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AiSessionService {

  private final AiSessionRepository aiSessionRepository;

  public AiSessionService(AiSessionRepository aiSessionRepository) {
    this.aiSessionRepository = aiSessionRepository;
  }

  public List<AiSessionResponse> getSessions(Long userId) {
    return aiSessionRepository.findByUserId(userId);
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
