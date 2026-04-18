package com.smartfirehub.notification.inbound;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Slack Events API 요청 서명 검증.
 *
 * <p>알고리즘: v0=hmac_sha256(signing_secret, "v0:{timestamp}:{body}") hex
 * <p>±5분 timestamp skew 검증 (replay 공격 방어), previous_signing_secret_enc + expires_at 기반
 * rotation grace 지원.
 *
 * <p>참고: https://api.slack.com/authentication/verifying-requests-from-slack
 */
@Component
public class SlackSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SlackSignatureVerifier.class);

    /** Slack 공식 권고: ±5분 이내의 요청만 유효 (replay 공격 방어) */
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final SlackWorkspaceRepository workspaceRepo;
    private final EncryptionService encryption;

    public SlackSignatureVerifier(SlackWorkspaceRepository workspaceRepo,
                                  EncryptionService encryption) {
        this.workspaceRepo = workspaceRepo;
        this.encryption = encryption;
    }

    /**
     * 요청 서명 검증. 성공 시 true 반환.
     *
     * @param teamId    Slack team_id (payload에서 추출).
     *                  url_verification은 team_id가 없을 수 있어 이 경우 별도 처리 필요.
     * @param timestamp X-Slack-Request-Timestamp 헤더 값 (유닉스 초)
     * @param body      raw 요청 본문 (검증 전 어떠한 파싱도 거치지 않은 원본)
     * @param signature X-Slack-Signature 헤더 값 (형식: "v0=<hex>")
     */
    public boolean verify(String teamId, String timestamp, String body, String signature) {
        // 1. timestamp 형식 및 skew 검증
        long tsEpoch;
        try {
            tsEpoch = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.warn("slack signature rejected — invalid timestamp format: {}", timestamp);
            return false;
        }

        long nowEpoch = Instant.now().getEpochSecond();
        if (Math.abs(nowEpoch - tsEpoch) > MAX_SKEW.toSeconds()) {
            log.warn("slack signature rejected — timestamp skew > {}s", MAX_SKEW.toSeconds());
            return false;
        }

        // 2. workspace 조회 — 알 수 없는 팀의 요청 차단
        var workspace = workspaceRepo.findByTeamId(teamId);
        if (workspace.isEmpty()) {
            log.warn("slack signature rejected — unknown team: {}", teamId);
            return false;
        }
        var ws = workspace.get();

        // 3. primary signing_secret 검증
        String primarySecret = encryption.decrypt(ws.signingSecretEnc());
        String expected = computeSignature(primarySecret, timestamp, body);
        if (MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
            return true;
        }

        // 4. previous_signing_secret rotation grace 검증
        //    Slack signing secret rotation 시 이전 secret도 일정 기간 유효하게 처리
        if (ws.previousSigningSecretEnc() != null
                && ws.previousSigningSecretExpiresAt() != null
                && ws.previousSigningSecretExpiresAt().isAfter(Instant.now())) {
            String prevSecret = encryption.decrypt(ws.previousSigningSecretEnc());
            String prevExpected = computeSignature(prevSecret, timestamp, body);
            if (MessageDigest.isEqual(prevExpected.getBytes(), signature.getBytes())) {
                log.info("slack signature verified via previous secret (rotation grace)");
                return true;
            }
        }

        log.warn("slack signature verification failed for team {}", teamId);
        return false;
    }

    /**
     * HMAC-SHA256으로 서명 계산.
     *
     * <p>서명 베이스 문자열 형식: "v0:{timestamp}:{body}"
     * <p>반환 형식: "v0={hex}" (Slack 헤더 형식과 동일)
     */
    private String computeSignature(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            byte[] hash = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes());
            return "v0=" + toHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("slack signature compute failed", e);
        }
    }

    /** 바이트 배열을 소문자 16진수 문자열로 변환 */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
