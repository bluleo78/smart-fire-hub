package com.smartfirehub.dataset.rowsearch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 데이터셋의 검색 대상 필드 목록(column_order 순). 색인 텍스트 조립과 설정 해시를 한곳에서 정의한다.
 *
 * <p>텍스트 라벨에 표시명을 쓰므로 표시명 변경은 데이터 행의 _updated_at 을 바꾸지 않아도 재색인이 필요하다 — 그래서
 * configHash 에 표시명을 포함한다.
 */
public record SearchConfig(List<Field> fields) {

  /** 임베딩 모델 입력 한도 대비 보수적 상한(초과분은 앞부분만 — 청크 분할은 후속). */
  public static final int MAX_SOURCE_CHARS = 8000;

  /** 검색 대상 필드 하나 — 컬럼명과 표시명. */
  public record Field(String columnName, String displayName) {
    /** 색인 텍스트 라벨: 표시명이 비어 있으면 컬럼명. */
    String label() {
      return displayName == null || displayName.isBlank() ? columnName : displayName;
    }
  }

  /** 검색 대상 필드가 하나도 없는지 — 비어 있으면 검색이 꺼진 것과 같다. */
  public boolean isEmpty() {
    return fields.isEmpty();
  }

  /** 검색 대상 컬럼명 목록(column_order 순). 원본 읽기와 상태 응답에 쓴다. */
  public List<String> columnNames() {
    return fields.stream().map(Field::columnName).toList();
  }

  /** (컬럼명, 표시명) 목록의 순서 포함 해시. 바뀌면 전체 재색인. */
  public String configHash() {
    StringBuilder sb = new StringBuilder();
    for (Field f : fields) sb.append(f.columnName()).append('|').append(f.label()).append('\n');
    return sha256(sb.toString());
  }

  /** "표시명: 값" 을 줄바꿈으로 잇는다. 빈 값 필드는 생략, 결과가 비면 "" (색인 행 삭제 대상). */
  public String buildSourceText(Map<String, Object> values) {
    StringBuilder sb = new StringBuilder();
    for (Field f : fields) {
      Object v = values.get(f.columnName());
      if (v == null || v.toString().isBlank()) continue;
      if (!sb.isEmpty()) sb.append('\n');
      sb.append(f.label()).append(": ").append(v.toString().strip());
    }
    return sb.length() > MAX_SOURCE_CHARS ? sb.substring(0, MAX_SOURCE_CHARS) : sb.toString();
  }

  /** UTF-8 문자열의 SHA-256 16진 문자열 — source_hash·config_hash 공용. */
  public static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
