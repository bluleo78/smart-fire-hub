package com.smartfirehub.document.repository.mybatis;

/**
 * document_file INSERT 파라미터 겸 생성 키 홀더(WD-712). {@code useGeneratedKeys} 가 생성된 id 를 {@code id} 에 써
 * 넣는다 — RETURNING 이 없는 Tibero 도 같은 JDBC 표준 경로다.
 *
 * <p>왜 getter 가 없나: MyBatis Reflector 는 getter·setter 없는 필드도 직접 읽고 쓴다 — 매퍼 XML 의 {@code
 * #{datasetId}} 등은 private final 필드를 읽고, 생성 키는 {@code id} 필드에 바로 쓴다(keyProperty="id"). record 는
 * 불변이라 생성 키를 받을 수 없어 이 최소 클래스를 쓴다.
 */
public class DocumentFileInsert {

  private final Long datasetId;
  private final String originalName;
  private final String mimeType;
  private final long fileSize;
  private final String storagePath;
  private final String checksum;
  private final Long uploadedBy;

  /** 생성 키 — INSERT 뒤 MyBatis 가 필드에 직접 채운다. */
  private Long id;

  /** INSERT 에 바인드할 값으로 홀더를 만든다 — id 는 INSERT 뒤 생성 키로 채워진다. */
  public DocumentFileInsert(
      Long datasetId,
      String originalName,
      String mimeType,
      long fileSize,
      String storagePath,
      String checksum,
      Long uploadedBy) {
    this.datasetId = datasetId;
    this.originalName = originalName;
    this.mimeType = mimeType;
    this.fileSize = fileSize;
    this.storagePath = storagePath;
    this.checksum = checksum;
    this.uploadedBy = uploadedBy;
  }

  /** INSERT 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }
}
