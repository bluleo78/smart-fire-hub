package com.smartfirehub.dataimport.dto;

/**
 * 엑셀 워크북의 시트 한 장 정보 (WD-253). 미리보기 응답의 {@code sheets} 목록 항목이며 워크북 탭 순서를 따른다.
 *
 * <p>화면·AI 가 「가져올 시트」를 고를 수 있게 이름과 숨김 여부만 싣는다. 숨김(hidden)과 매우 숨김(veryHidden)은 둘 다 {@code
 * hidden=true} 다 — 기본 선택에서 똑같이 건너뛰며, 사용자가 이름으로 명시하면 읽을 수 있다.
 *
 * @param name 시트 이름(엑셀 탭에 보이는 그대로)
 * @param hidden 숨긴 시트이면 true
 */
public record ExcelSheetInfo(String name, boolean hidden) {}
