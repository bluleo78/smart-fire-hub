package com.smartfirehub.pipeline.service.validator;

import com.smartfirehub.pipeline.exception.UnsafePythonScriptException;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 PYTHON 스텝의 escalation 코드 탐지·차단기. (#270)
 *
 * <p><b>정책 (allow-by-default, 좁은 deny-list)</b>: Python 스텝은 입력 데이터를 DB 에서 직접 읽고(psycopg2/DB_URL),
 * 외부 API 를 호출(urllib)하고, pandas/numpy 로 가공하는 것이 <b>문서화된 정당 기능</b>이다. 따라서 import 화이트리스트나 네트워크/DB 차단은
 * 하지 않는다. 대신 ETL 에 정당하게 쓰일 일이 전혀 없는 <b>shell/동적 코드 실행·네이티브 코드 로딩</b> 원시함수만 차단한다.
 *
 * <p>차단 대상:
 *
 * <ul>
 *   <li>외부 프로세스 실행: {@code subprocess}, {@code os.system}, {@code os.popen}, {@code os.exec*},
 *       {@code os.spawn*}, {@code pty}
 *   <li>동적 코드 실행: builtin {@code eval()}, {@code exec()}, {@code compile()} (pandas {@code
 *       df.eval()} 같은 메서드 호출은 제외)
 *   <li>동적 import: {@code __import__()}, {@code importlib}
 *   <li>네이티브 코드 로딩: {@code ctypes}
 * </ul>
 *
 * <p><b>한계</b>: 문자열 기반 탐지는 우회 가능(예: {@code getattr} 재구성)하므로 <b>이것은 보안 경계가 아니다</b>. 실제 격리는 executor
 * 의 nsjail + {@code pipeline_executor} 역할(data 스키마 한정) + env 격리가 담당한다. 본 검증기의 목적은 (a) escalation
 * 시도의 조기 탐지·명확한 거부, (b) nsjail 비활성 경로(개발)에 대한 보강이다.
 */
@Slf4j
@Component
public class PythonScriptValidator {

  /**
   * 차단 패턴 목록 (패턴, 사람이 읽는 사유).
   *
   * <p>false-positive 방지 가이드:
   *
   * <ul>
   *   <li>builtin {@code eval/exec/compile}: 앞에 {@code .} 나 단어문자가 없을 때만 매칭 → pandas {@code
   *       df.eval(} / 변수명 {@code executor} 오탐 방지.
   *   <li>{@code os.system} 류: {@code os} 와 멤버 사이 공백 허용, 단어 경계 적용.
   * </ul>
   */
  private static final List<BlockedPattern> BLOCKED_PATTERNS =
      List.of(
          new BlockedPattern(
              Pattern.compile("(?m)^\\s*(import\\s+subprocess|from\\s+subprocess\\b)"),
              "subprocess 모듈 (외부 프로세스 실행)"),
          new BlockedPattern(Pattern.compile("\\bsubprocess\\s*\\."), "subprocess 호출 (외부 프로세스 실행)"),
          new BlockedPattern(
              Pattern.compile("\\bos\\s*\\.\\s*(system|popen|exec[lv][pe]*|spawn\\w*)\\b"),
              "os.system/popen/exec/spawn (셸·외부 프로세스 실행)"),
          new BlockedPattern(
              Pattern.compile("(?<![\\w.])eval\\s*\\("), "builtin eval() (동적 코드 실행)"),
          new BlockedPattern(
              Pattern.compile("(?<![\\w.])exec\\s*\\("), "builtin exec() (동적 코드 실행)"),
          new BlockedPattern(
              Pattern.compile("(?<![\\w.])compile\\s*\\("), "builtin compile() (동적 코드 실행)"),
          new BlockedPattern(Pattern.compile("\\b__import__\\s*\\("), "__import__() (동적 import)"),
          new BlockedPattern(
              Pattern.compile(
                  "(?m)^\\s*(import\\s+importlib|from\\s+importlib\\b)|\\bimportlib\\s*\\."),
              "importlib (동적 import)"),
          new BlockedPattern(
              Pattern.compile("(?m)^\\s*(import\\s+ctypes|from\\s+ctypes\\b)|\\bctypes\\s*\\."),
              "ctypes (네이티브 코드 로딩)"),
          new BlockedPattern(
              Pattern.compile("(?m)^\\s*(import\\s+pty|from\\s+pty\\b)"), "pty (의사 터미널 셸)"));

  /** 검증 실패 시 {@link UnsafePythonScriptException} 을 던진다. */
  public void validate(String scriptContent) {
    if (scriptContent == null || scriptContent.isBlank()) {
      // 빈 스크립트는 본 검증기의 관심사가 아니다 (다른 검증/실행 단계에서 처리).
      return;
    }

    for (BlockedPattern bp : BLOCKED_PATTERNS) {
      if (bp.pattern().matcher(scriptContent).find()) {
        // escalation 시도는 security 로그로 남겨 사후 추적/알람이 가능하게 한다.
        log.warn("[SECURITY] Python 스텝 escalation 패턴 차단: {}", bp.reason());
        throw new UnsafePythonScriptException(
            "Python 스크립트에 허용되지 않은 코드가 포함되어 있습니다: "
                + bp.reason()
                + ". 데이터 조회는 DB_URL(psycopg2)·외부 API(urllib), 가공은 pandas/numpy 를 사용하세요. "
                + "셸 실행·동적 코드 실행·권한 우회는 금지됩니다.");
      }
    }
  }

  /** 차단 패턴과 사유 쌍. */
  private record BlockedPattern(Pattern pattern, String reason) {}
}
