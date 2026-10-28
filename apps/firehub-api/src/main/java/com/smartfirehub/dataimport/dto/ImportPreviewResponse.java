package com.smartfirehub.dataimport.dto;

import java.util.List;
import java.util.Map;

/**
 * 가져오기 미리보기 응답.
 *
 * @param sheetName 엑셀에서 실제로 읽은 시트 이름(WD-253). CSV 는 null
 * @param sheets 엑셀 워크북의 시트 목록(탭 순서, 숨김 여부 포함). CSV 는 null — 화면이 2장 이상일 때 「가져올 시트」 선택을 보여 준다
 */
public record ImportPreviewResponse(
    List<String> fileHeaders,
    List<Map<String, String>> sampleRows,
    List<ColumnMappingDto> suggestedMappings,
    int totalRows,
    String sheetName,
    List<ExcelSheetInfo> sheets) {

  /** 시트 정보가 없는(CSV) 응답용 생성자 — 기존 호출부 호환. */
  public ImportPreviewResponse(
      List<String> fileHeaders,
      List<Map<String, String>> sampleRows,
      List<ColumnMappingDto> suggestedMappings,
      int totalRows) {
    this(fileHeaders, sampleRows, suggestedMappings, totalRows, null, null);
  }
}
