package com.smartfirehub.vectorstore.qdrant;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Qdrant 포인트 id 를 대상에서 결정적으로 만든다(ADR 0002 결정 7).
 *
 * <p>(종류, tenant, 대상 id[, chunk_no, generation]) 을 이름 문자열로 이어 RFC 4122 UUIDv5(SHA-1)를 만든다. 같은 대상을
 * 다시 쓰면 같은 id 라 덮어쓰기(upsert)가 되고, 아웃박스 워커·대조 스윕이 플랫폼 DB 의 id 만으로 포인트를 찾는다. Java 의 {@link
 * UUID#nameUUIDFromBytes} 는 v3(MD5)라 쓰지 않는다.
 *
 * <p><b>저장된 데이터에 박히는 규칙이다.</b> 네임스페이스·이름 형식을 바꾸면 기존 포인트를 찾지 못한다. 단위 테스트가 Python {@code uuid.uuid5}
 * 로 뽑은 값으로 고정한다.
 */
public final class QdrantPointIds {

  /** 이 앱 포인트 id 전용 네임스페이스(임의로 정해 고정한 값). */
  static final UUID NAMESPACE = UUID.fromString("6f1d2c4e-8b3a-4f57-9e21-3c7a5b9d0e18");

  private QdrantPointIds() {}

  /** 데이터셋 카탈로그 포인트 — {@code dataset:{tenant}:{datasetId}}. */
  public static UUID dataset(long tenantId, long datasetId) {
    return uuid5("dataset:" + tenantId + ":" + datasetId);
  }

  /** 문서 청크 포인트 — {@code chunk:{tenant}:{chunkId}}. 청크 id 는 플랫폼 DB {@code document_chunk.id} 다. */
  public static UUID chunk(long tenantId, long chunkId) {
    return uuid5("chunk:" + tenantId + ":" + chunkId);
  }

  /**
   * 행 검색 포인트 — {@code row:{tenant}:{datasetId}:{rowId}:{chunkNo}:{generation}}. 세대가 다르면 다른 포인트라 재구축
   * 중에도 옛 세대와 겹치지 않는다(결정 9).
   */
  public static UUID row(long tenantId, long datasetId, long rowId, int chunkNo, long generation) {
    return uuid5(
        "row:" + tenantId + ":" + datasetId + ":" + rowId + ":" + chunkNo + ":" + generation);
  }

  /** RFC 4122 §4.3 이름 기반 UUID, SHA-1(버전 5). */
  static UUID uuid5(String name) {
    MessageDigest sha1;
    try {
      sha1 = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 을 쓸 수 없습니다", e);
    }
    ByteBuffer ns = ByteBuffer.allocate(16);
    ns.putLong(NAMESPACE.getMostSignificantBits()).putLong(NAMESPACE.getLeastSignificantBits());
    sha1.update(ns.array());
    sha1.update(name.getBytes(StandardCharsets.UTF_8));
    byte[] h = sha1.digest();
    h[6] = (byte) ((h[6] & 0x0f) | 0x50); // 버전 5
    h[8] = (byte) ((h[8] & 0x3f) | 0x80); // RFC 4122 변형
    ByteBuffer bb = ByteBuffer.wrap(h, 0, 16);
    return new UUID(bb.getLong(), bb.getLong());
  }
}
