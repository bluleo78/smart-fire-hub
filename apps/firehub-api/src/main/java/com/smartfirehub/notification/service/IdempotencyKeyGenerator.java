package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** correlationId + channel + recipient(userId 또는 'ext') 기반 SHA-256 멱등성 키 생성. */
@Component
public class IdempotencyKeyGenerator {

  /** key = sha256(correlationId|channel|recipientUserId).hex 앞 64자. */
  public String generate(UUID correlationId, ChannelType channel, Long recipientUserId) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String src =
          correlationId + "|" + channel + "|" + (recipientUserId == null ? "ext" : recipientUserId);
      byte[] hash = md.digest(src.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 64);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** advisory(안내) 메시지를 본문 발송과 분리하기 위한 별도 키 변형. */
  public String generateAdvisory(UUID correlationId, Long recipientUserId) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String src =
          correlationId + "|ADVISORY|" + (recipientUserId == null ? "ext" : recipientUserId);
      byte[] hash = md.digest(src.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 64);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
