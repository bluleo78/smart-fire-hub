package com.smartfirehub.notification.inbound;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository.SlackWorkspace;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SlackSignatureVerifier 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 Mockito만 사용하는 순수 단위 테스트.
 * 실제 HMAC-SHA256 계산으로 알려진 서명 값을 생성하여 검증.
 */
@ExtendWith(MockitoExtension.class)
class SlackSignatureVerifierTest {

    private static final String TEAM_ID = "T123ABC";
    private static final String PRIMARY_SECRET = "test-secret-primary";
    private static final String PREV_SECRET = "test-secret-previous";
    private static final String BODY = "{\"type\":\"event_callback\"}";

    /** 암호화된 값 placeholder — EncryptionService는 mock이므로 실제 암호화 불필요 */
    private static final String PRIMARY_SECRET_ENC = "enc:primary";
    private static final String PREV_SECRET_ENC = "enc:previous";

    @Mock
    private SlackWorkspaceRepository workspaceRepo;

    @Mock
    private EncryptionService encryption;

    private SlackSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new SlackSignatureVerifier(workspaceRepo, encryption);
    }

    // -----------------------------------------------------------------------
    // 정상 케이스
    // -----------------------------------------------------------------------

    @Test
    void verify_validSignature_returnsTrue() throws Exception {
        // 현재 시각 기준 timestamp 생성
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = computeSignature(PRIMARY_SECRET, timestamp, BODY);

        stubPrimaryWorkspace();
        when(encryption.decrypt(PRIMARY_SECRET_ENC)).thenReturn(PRIMARY_SECRET);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isTrue();
    }

    @Test
    void verify_primaryFailsPreviousValid_returnsTrue() throws Exception {
        // primary secret은 다른 secret으로 서명 → mismatch
        // previous secret으로 서명 → match, expires 미도래
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = computeSignature(PREV_SECRET, timestamp, BODY);

        // previous expires_at: 1시간 후 (grace 기간 내)
        stubWorkspace(PRIMARY_SECRET_ENC, PREV_SECRET_ENC, Instant.now().plusSeconds(3600));
        when(encryption.decrypt(PRIMARY_SECRET_ENC)).thenReturn(PRIMARY_SECRET);
        when(encryption.decrypt(PREV_SECRET_ENC)).thenReturn(PREV_SECRET);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isTrue();
    }

    // -----------------------------------------------------------------------
    // 실패 케이스 — timestamp
    // -----------------------------------------------------------------------

    @Test
    void verify_timestampTooOld_returnsFalse() throws Exception {
        // 10분 전 timestamp → MAX_SKEW(5분) 초과
        String timestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        String signature = computeSignature(PRIMARY_SECRET, timestamp, BODY);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isFalse();
    }

    @Test
    void verify_timestampFuture_returnsFalse() throws Exception {
        // 10분 후 timestamp → MAX_SKEW(5분) 초과
        String timestamp = String.valueOf(Instant.now().plusSeconds(600).getEpochSecond());
        String signature = computeSignature(PRIMARY_SECRET, timestamp, BODY);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isFalse();
    }

    @Test
    void verify_invalidTimestampFormat_returnsFalse() {
        assertThat(verifier.verify(TEAM_ID, "not-a-number", BODY, "v0=abc")).isFalse();
    }

    // -----------------------------------------------------------------------
    // 실패 케이스 — 서명 불일치
    // -----------------------------------------------------------------------

    @Test
    void verify_tamperedSignature_returnsFalse() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = computeSignature(PRIMARY_SECRET, timestamp, BODY);

        // 마지막 문자 1개를 변경하여 서명 변조
        String tampered = signature.substring(0, signature.length() - 1) + "x";

        stubPrimaryWorkspace();
        when(encryption.decrypt(PRIMARY_SECRET_ENC)).thenReturn(PRIMARY_SECRET);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, tampered)).isFalse();
    }

    @Test
    void verify_primaryAndPreviousFail_returnsFalse() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        // 완전히 다른 secret으로 서명 생성
        String signature = computeSignature("wrong-secret", timestamp, BODY);

        stubWorkspace(PRIMARY_SECRET_ENC, PREV_SECRET_ENC, Instant.now().plusSeconds(3600));
        when(encryption.decrypt(PRIMARY_SECRET_ENC)).thenReturn(PRIMARY_SECRET);
        when(encryption.decrypt(PREV_SECRET_ENC)).thenReturn(PREV_SECRET);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isFalse();
    }

    @Test
    void verify_previousSecretExpired_returnsFalse() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = computeSignature(PREV_SECRET, timestamp, BODY);

        // previous expires_at: 1시간 전 (grace 기간 만료)
        stubWorkspace(PRIMARY_SECRET_ENC, PREV_SECRET_ENC, Instant.now().minusSeconds(3600));
        when(encryption.decrypt(PRIMARY_SECRET_ENC)).thenReturn(PRIMARY_SECRET);

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isFalse();
    }

    // -----------------------------------------------------------------------
    // 실패 케이스 — 알 수 없는 팀
    // -----------------------------------------------------------------------

    @Test
    void verify_unknownTeam_returnsFalse() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = computeSignature(PRIMARY_SECRET, timestamp, BODY);

        when(workspaceRepo.findByTeamId(TEAM_ID)).thenReturn(Optional.empty());

        assertThat(verifier.verify(TEAM_ID, timestamp, BODY, signature)).isFalse();
    }

    // -----------------------------------------------------------------------
    // 헬퍼 메서드
    // -----------------------------------------------------------------------

    /** primary secret만 있는 워크스페이스 stub */
    private void stubPrimaryWorkspace() {
        stubWorkspace(PRIMARY_SECRET_ENC, null, null);
    }

    /** previous secret + expires_at 포함 워크스페이스 stub */
    private void stubWorkspace(String primaryEnc, String prevEnc, Instant prevExpires) {
        var ws = new SlackWorkspace(
                1L, TEAM_ID, "Test Workspace", "U_BOT",
                "bot-token-enc",
                primaryEnc,
                prevEnc,
                prevExpires,
                null
        );
        when(workspaceRepo.findByTeamId(TEAM_ID)).thenReturn(Optional.of(ws));
    }

    /**
     * 테스트용 HMAC-SHA256 서명 계산.
     *
     * <p>SlackSignatureVerifier.computeSignature()와 동일한 로직.
     */
    private static String computeSignature(String secret, String timestamp, String body)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        byte[] hash = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes());
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return "v0=" + sb.toString();
    }
}
