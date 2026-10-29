package com.smartfirehub.dataimport.exception;

import com.smartfirehub.dataimport.dto.ExcelSheetInfo;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 엑셀에서 읽을 시트를 정하지 못했을 때 던진다 (WD-253) — 지정한 이름의 시트가 없거나, 지정하지 않았는데 보이는 시트가 하나도 없을 때.
 *
 * <p>{@link FileUnreadableException} 을 상속하는 이유: 미리보기·검증 응답은 기존 400 핸들러가, 임포트 잡은 실패 사유 변환({@code
 * toUserFriendlyMessage})이 이 한국어 문구를 그대로 보여 준다. 파서의 「파일을 읽을 수 없습니다」 일괄 변환도 이 예외는 그대로 통과시킨다. 문구에 파일의
 * 시트 목록을 함께 실어, 사용자·AI 가 다시 고를 이름을 바로 알 수 있게 한다.
 */
public class ExcelSheetSelectionException extends FileUnreadableException {

  /** 오류 문구에 되풀이할 요청 시트 이름의 최대 길이. 엑셀 시트 이름은 31자까지라 그보다 긴 입력은 잘라 보인다. */
  private static final int MAX_ECHO_LENGTH = 31;

  private ExcelSheetSelectionException(String userMessage) {
    super(userMessage, null);
  }

  /** 지정한 이름의 시트가 없을 때. */
  public static ExcelSheetSelectionException notFound(
      String requested, List<ExcelSheetInfo> sheets) {
    return new ExcelSheetSelectionException(
        "'" + shorten(requested) + "' 시트를 찾을 수 없습니다. 파일에 있는 시트: " + describe(sheets));
  }

  /** 공백·대소문자를 무시하고 비교했을 때 요청한 이름과 맞는 시트가 여럿이라 하나로 정할 수 없을 때. */
  public static ExcelSheetSelectionException ambiguous(
      String requested, List<ExcelSheetInfo> sheets) {
    return new ExcelSheetSelectionException(
        "'"
            + shorten(requested)
            + "' 과(와) 이름이 같은 시트가 여러 개입니다(공백·대소문자 차이). 정확한 시트 이름을 지정해 주세요. 파일에 있는 시트: "
            + describe(sheets));
  }

  /** 시트를 지정하지 않았는데 모든 시트가 숨겨져 있을 때. */
  public static ExcelSheetSelectionException noVisibleSheet(List<ExcelSheetInfo> sheets) {
    return new ExcelSheetSelectionException(
        "보이는 시트가 없습니다. 엑셀에서 가져올 시트의 숨기기를 해제하거나 가져올 시트 이름을 지정해 주세요. 파일에 있는 시트: " + describe(sheets));
  }

  /**
   * 워크북의 시트 목록을 읽지 못해 지정한 시트를 고를 수 없을 때(손상된 xlsb 시트 목록 등). 시트를 지정하지 않으면 예전처럼 첫 시트를 읽을 수 있음을 알려 준다.
   */
  public static ExcelSheetSelectionException sheetListUnreadable(String requested) {
    return new ExcelSheetSelectionException(
        "파일의 시트 목록을 읽을 수 없어 '"
            + shorten(requested)
            + "' 시트를 고를 수 없습니다. 시트를 지정하지 않으면 첫 번째 시트를 가져옵니다.");
  }

  /** 오류 문구에 나열할 시트 수의 상한 — 시트가 수백 장인 파일에서 문구(토스트·AI 응답)가 끝없이 길어지지 않게 한다. */
  static final int MAX_LISTED_SHEETS = 20;

  /** 요청한 이름을 오류 문구에 되풀이할 길이로 자른다. */
  private static String shorten(String requested) {
    return requested.length() > MAX_ECHO_LENGTH
        ? requested.substring(0, MAX_ECHO_LENGTH) + "…"
        : requested;
  }

  /** 시트 목록을 「이름, 이름(숨김)」 형태로 나열한다. 앞 {@value #MAX_LISTED_SHEETS}장만 보이고 나머지는 「외 N개」로 줄인다. */
  private static String describe(List<ExcelSheetInfo> sheets) {
    if (sheets.isEmpty()) return "(없음)";
    String listed =
        sheets.stream()
            .limit(MAX_LISTED_SHEETS)
            .map(s -> s.hidden() ? s.name() + "(숨김)" : s.name())
            .collect(Collectors.joining(", "));
    int rest = sheets.size() - MAX_LISTED_SHEETS;
    return rest > 0 ? listed + " 외 " + rest + "개" : listed;
  }
}
