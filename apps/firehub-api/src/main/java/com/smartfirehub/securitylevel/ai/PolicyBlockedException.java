package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.securitylevel.access.DatasetAction;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * AI·공유 정책 차단(스펙 §4.3 차단 응답 계약). 본문: code=POLICY_BLOCKED, errors={action, levelName, policyKey}.
 *
 * <p>등급 이름은 사용자가 이미 볼 수 있는(VIEW 통과) 데이터셋에 대해서만 실린다 — VIEW 실패는 이 예외가 아니라 404(존재 은닉)다. 코드 상수는 흐름 간 공통
 * 결정 R6 에 따라 이 클래스가 정본이다.
 */
public class PolicyBlockedException extends CodedApiException {

  /** 차단 응답 코드(웹·ai-agent 가 이 문자열로 차단 안내를 구분한다). */
  public static final String CODE = "POLICY_BLOCKED";

  public PolicyBlockedException(DatasetAction action, String levelName, String policyKey) {
    super(
        HttpStatus.FORBIDDEN,
        CODE,
        message(action, levelName),
        Map.of("action", action.name(), "levelName", levelName, "policyKey", policyKey));
  }

  /** 한국어 사용자 문구 — 공유 차단과 AI 차단을 구분한다. */
  private static String message(DatasetAction action, String levelName) {
    return action == DatasetAction.SHARE
        ? "'" + levelName + "' 등급 데이터는 공유 저장소·외부 발송에 쓸 수 없습니다"
        : "'" + levelName + "' 등급 데이터는 현재 AI 공급자로 보낼 수 없습니다";
  }
}
