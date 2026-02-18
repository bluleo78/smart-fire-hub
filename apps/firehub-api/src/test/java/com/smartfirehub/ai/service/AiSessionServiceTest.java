package com.smartfirehub.ai.service;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class AiSessionServiceTest extends IntegrationTestBase {

    @Autowired
    private AiSessionService aiSessionService;

    @Autowired
    private AuthService authService;

    @Test
    void getSessions_returnsSessionsForUser() {
        UserResponse user = authService.signup(new SignupRequest("testuser", "test@example.com", "password123", "Test User"));
        Long userId = user.id();

        aiSessionService.createSession(userId, new CreateAiSessionRequest("session-001", null, null, "My Session"));

        List<AiSessionResponse> sessions = aiSessionService.getSessions(userId);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).sessionId()).isEqualTo("session-001");
        assertThat(sessions.get(0).userId()).isEqualTo(userId);
    }

    @Test
    void createSession_success() {
        UserResponse user = authService.signup(new SignupRequest("testuser", "test@example.com", "password123", "Test User"));
        Long userId = user.id();

        AiSessionResponse result = aiSessionService.createSession(userId,
                new CreateAiSessionRequest("session-abc", "dataset", 42L, "Dataset Chat"));

        assertThat(result.id()).isNotNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.sessionId()).isEqualTo("session-abc");
        assertThat(result.contextType()).isEqualTo("dataset");
        assertThat(result.contextResourceId()).isEqualTo(42L);
        assertThat(result.title()).isEqualTo("Dataset Chat");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();
    }

    @Test
    void verifySessionOwnership_ownSession_noException() {
        UserResponse user = authService.signup(new SignupRequest("testuser", "test@example.com", "password123", "Test User"));
        Long userId = user.id();

        aiSessionService.createSession(userId, new CreateAiSessionRequest("session-own", null, null, "My Session"));

        // Should not throw
        aiSessionService.verifySessionOwnership(userId, "session-own");
    }

    @Test
    void verifySessionOwnership_otherUser_throwsAccessDenied() {
        UserResponse user1 = authService.signup(new SignupRequest("user1", "user1@example.com", "password123", "User 1"));
        UserResponse user2 = authService.signup(new SignupRequest("user2", "user2@example.com", "password123", "User 2"));

        aiSessionService.createSession(user1.id(), new CreateAiSessionRequest("session-u1", null, null, "User1 Session"));

        assertThatThrownBy(() -> aiSessionService.verifySessionOwnership(user2.id(), "session-u1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateSessionTitle_ownSession_success() {
        UserResponse user = authService.signup(new SignupRequest("testuser", "test@example.com", "password123", "Test User"));
        Long userId = user.id();

        AiSessionResponse created = aiSessionService.createSession(userId,
                new CreateAiSessionRequest("session-upd", null, null, "Original Title"));

        aiSessionService.updateSessionTitle(userId, created.id(), "Updated Title");

        List<AiSessionResponse> sessions = aiSessionService.getSessions(userId);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).title()).isEqualTo("Updated Title");
    }

    @Test
    void updateSessionTitle_otherUser_throwsAccessDenied() {
        UserResponse user1 = authService.signup(new SignupRequest("user1", "user1@example.com", "password123", "User 1"));
        UserResponse user2 = authService.signup(new SignupRequest("user2", "user2@example.com", "password123", "User 2"));

        AiSessionResponse created = aiSessionService.createSession(user1.id(),
                new CreateAiSessionRequest("session-u1", null, null, "User1 Session"));

        assertThatThrownBy(() -> aiSessionService.updateSessionTitle(user2.id(), created.id(), "Stolen Title"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteSession_ownSession_success() {
        UserResponse user = authService.signup(new SignupRequest("testuser", "test@example.com", "password123", "Test User"));
        Long userId = user.id();

        AiSessionResponse created = aiSessionService.createSession(userId,
                new CreateAiSessionRequest("session-del", null, null, "To Delete"));

        aiSessionService.deleteSession(userId, created.id());

        List<AiSessionResponse> sessions = aiSessionService.getSessions(userId);
        assertThat(sessions).isEmpty();
    }

    @Test
    void deleteSession_otherUser_throwsAccessDenied() {
        UserResponse user1 = authService.signup(new SignupRequest("user1", "user1@example.com", "password123", "User 1"));
        UserResponse user2 = authService.signup(new SignupRequest("user2", "user2@example.com", "password123", "User 2"));

        AiSessionResponse created = aiSessionService.createSession(user1.id(),
                new CreateAiSessionRequest("session-u1", null, null, "User1 Session"));

        assertThatThrownBy(() -> aiSessionService.deleteSession(user2.id(), created.id()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
