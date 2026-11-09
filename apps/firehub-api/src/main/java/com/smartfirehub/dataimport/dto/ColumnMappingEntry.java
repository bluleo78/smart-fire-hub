package com.smartfirehub.dataimport.dto;

import java.util.List;

/**
 * 임포트 열 매핑 한 항목 — 파일 열(fileColumn)을 데이터셋 열(datasetColumn)에 잇는다.
 *
 * <p>datasetColumn 이 null 이면 「이 파일 열은 가져오지 않음」이고, fileColumn 이 빈 문자열이면 이름이 빈 헤더 열(WD-369)이다 — 둘 다 웹
 * 가져오기 화면이 정상적으로 보내는 모양이다.
 */
public record ColumnMappingEntry(String fileColumn, String datasetColumn) {

  /** 매핑 목록에 null 항목이나 파일 열(fileColumn)이 없는 항목이 있을 때의 400 문구(WD-608). */
  public static final String INCOMPLETE_MAPPING_MESSAGE =
      "열 매핑(mappings)에 빈(null) 항목이나 파일 열(fileColumn)이 없는 항목이 있습니다. "
          + "각 항목은 {\"fileColumn\": \"파일 열\", \"datasetColumn\": \"데이터셋 열\"} 객체여야 합니다"
          + "(가져오지 않을 열은 datasetColumn 을 null 로 보내 주세요).";

  /**
   * 매핑 목록의 모든 항목이 완전한지 확인하고, 아니면 {@link IllegalArgumentException}(400) 을 던진다(WD-608).
   *
   * <ul>
   *   <li>null 항목 — 검증·적재가 매핑을 꺼내다 NPE 가 났다(사전 검증 500, 임포트는 접수 뒤 잡 실패).
   *   <li>fileColumn 이 null(키 누락)인 항목 — 어느 파일 열과도 맞지 않아, datasetColumn 을 지정해도 그 열이 경고 없이 빈 값으로
   *       적재됐다. 파일 헤더는 늘 문자열(빈 이름 열은 "")이라 정상 클라이언트는 null 을 보내지 않는다.
   * </ul>
   *
   * <p>목록 자체가 null 이면(매핑 없음) 통과시킨다 — 매핑 없는 임포트는 열 이름으로 바로 맞추는 정상 경로다. 서비스
   * 진입점(validateImport·importFile — 컨트롤러는 JSON 만 읽고 여기에 맡긴다)과 잡이 저장된 매핑을 다시 읽는 곳이 모두 이 한 규칙을 쓴다.
   */
  public static void requireComplete(List<ColumnMappingEntry> mappings) {
    if (mappings == null) {
      return;
    }
    for (ColumnMappingEntry m : mappings) {
      if (m == null || m.fileColumn() == null) {
        throw new IllegalArgumentException(INCOMPLETE_MAPPING_MESSAGE);
      }
    }
  }
}
