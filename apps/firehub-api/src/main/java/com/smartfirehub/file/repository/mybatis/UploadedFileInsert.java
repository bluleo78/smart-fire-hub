package com.smartfirehub.file.repository.mybatis;

import java.time.Instant;

/**
 * uploaded_files INSERT 파라미터 겸 생성 키 홀더(WD-712). {@code useGeneratedKeys} 가 생성된 id 를 {@code id} 에 써
 * 넣는다 — RETURNING 이 없는 Tibero 도 같은 JDBC 표준 경로다. 시각은 {@link Instant} 로 받는다(Tibero 는 UTC 벽시계
 * TIMESTAMP 라 전역 핸들러가 JVM 시간대와 무관하게 바인딩한다).
 *
 * <p>왜 getter 가 없나: MyBatis Reflector 는 getter·setter 없는 필드도 직접 읽고 쓴다 — 매퍼 XML 의 {@code
 * #{originalName}} 등은 private final 필드를 읽고(필드 타입 Instant 로 전역 핸들러가 골라진다), 생성 키는 {@code id} 필드에 바로
 * 쓴다(keyProperty="id"). record 는 불변이라 생성 키를 받을 수 없어 이 최소 클래스를 쓴다.
 */
public class UploadedFileInsert {

  private final String originalName;
  private final String storedName;
  private final String mimeType;
  private final long fileSize;
  private final String category;
  private final String storagePath;
  private final Long userId;
  private final Instant createdAt;
  private final Instant expiresAt;

  /** 생성 키 — INSERT 뒤 MyBatis 가 필드에 직접 채운다. */
  private Long id;

  /** INSERT 에 바인드할 값으로 홀더를 만든다 — id 는 INSERT 뒤 생성 키로 채워진다. */
  public UploadedFileInsert(
      String originalName,
      String storedName,
      String mimeType,
      long fileSize,
      String category,
      String storagePath,
      Long userId,
      Instant createdAt,
      Instant expiresAt) {
    this.originalName = originalName;
    this.storedName = storedName;
    this.mimeType = mimeType;
    this.fileSize = fileSize;
    this.category = category;
    this.storagePath = storagePath;
    this.userId = userId;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  /** INSERT 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }
}
