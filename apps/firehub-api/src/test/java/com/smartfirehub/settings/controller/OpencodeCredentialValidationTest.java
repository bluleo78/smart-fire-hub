package com.smartfirehub.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.controller.OpencodeCredentialValidation.Problem;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult.Reason;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@link OpencodeCredentialValidation} 순수 유닛 테스트 — Spring/DB 없이 빠르게 전체 {@link Reason}
 * 값을 전수 검사한다.
 *
 * <p><b>왜 전수인가.</b> 이 브랜치의 반복된 실패 패턴이 "열거형 값은 선언했지만 일부만 단언한다"
 * 였다(브리프 「테스팅」 절). {@link Reason} 은 12개 값(OK 포함)이고, 이 테스트는 <b>정확히
 * 12개</b>를 한 번에 단언한다 — {@code Reason.values()} 를 순회하며 기대 테이블에 없는 값이
 * 있으면 그 자체로 테스트가 실패하게 만들어(맵 조회가 없으면 NPE) "새 값을 추가하고 매핑표를
 * 깜빡하는" 사고를 잡는다.
 */
class OpencodeCredentialValidationTest {

  /**
   * {@link OpencodeCredentialValidation#statusFor} 의 기대값 표. 본문 javadoc 의 버킷 근거를 그대로
   * 옮긴다 — 두 문서가 갈리면(코드를 고치고 여기를 안 고치면) 이 테스트가 즉시 RED 가 된다.
   */
  private static final Map<Reason, HttpStatus> EXPECTED =
      new EnumMap<>(
          Map.ofEntries(
              Map.entry(Reason.INVALID_URL, HttpStatus.BAD_REQUEST),
              Map.entry(Reason.SCHEME_NOT_ALLOWED, HttpStatus.BAD_REQUEST),
              Map.entry(Reason.PORT_NOT_ALLOWED, HttpStatus.BAD_REQUEST),
              Map.entry(Reason.BLOCKED_ADDRESS, HttpStatus.BAD_REQUEST),
              Map.entry(Reason.UNRESOLVED_HOST, HttpStatus.BAD_GATEWAY),
              Map.entry(Reason.REDIRECT_BLOCKED, HttpStatus.BAD_GATEWAY),
              Map.entry(Reason.UNREACHABLE, HttpStatus.BAD_GATEWAY),
              Map.entry(Reason.PROVIDER_REJECTED, HttpStatus.UNPROCESSABLE_ENTITY),
              Map.entry(Reason.PARSE_ERROR, HttpStatus.UNPROCESSABLE_ENTITY),
              Map.entry(Reason.TOO_LARGE, HttpStatus.UNPROCESSABLE_ENTITY),
              Map.entry(Reason.TIMEOUT, HttpStatus.GATEWAY_TIMEOUT)));

  /**
   * 전수 검사 + 버킷 분리 검사를 한 테스트에 묶는다. 뮤테이션: 서로 다른 두 {@code Reason} 값을
   * 같은 상태로 뭉개면(예: {@code PROVIDER_REJECTED} 를 502 로 바꾸면) 위 {@code EXPECTED} 표와
   * 실제 출력이 달라 즉시 실패한다.
   */
  @Test
  void statusFor_는_12개_값_전부에_상태를_준다() {
    assertThat(Reason.values()).hasSize(12); // 표 자체가 낡지 않았는지(값이 늘었는데 표를 안 고쳤는지)

    for (Reason reason : Reason.values()) {
      if (reason == Reason.OK) continue;
      HttpStatus expected = EXPECTED.get(reason);
      assertThat(expected)
          .as("Reason.%s 에 대한 기대 상태가 EXPECTED 표에 없다 — 새 값을 추가했다면 이 표부터 채운다", reason)
          .isNotNull();
      assertThat(OpencodeCredentialValidation.statusFor(reason))
          .as("Reason.%s", reason)
          .isEqualTo(expected);
    }

    // EXPECTED 표 자체도 11개(OK 제외 전부)여야 한다 — 표에 빠진 값이 있으면 위 루프가 그 값을
    // 건너뛰고 조용히 통과해 버리므로, 개수를 별도로 못박는다.
    assertThat(EXPECTED).hasSize(11);
  }

  @Test
  void statusFor_는_OK를_받으면_오류를_던진다() {
    // OK 는 "오류 상태"가 아니므로 매핑 자체가 없다 — 호출부가 ok() 를 먼저 확인했어야 하는
    // 프로그래머 실수를 여기서 잡는다.
    assertThatThrownBy(() -> OpencodeCredentialValidation.statusFor(Reason.OK))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reasoningEffort_생략은_통과() {
    assertThat(OpencodeCredentialValidation.checkReasoningEffort(Map.of())).isEmpty();
  }

  @Test
  void reasoningEffort_빈값은_통과() {
    assertThat(OpencodeCredentialValidation.checkReasoningEffort(Map.of("reasoningEffort", "")))
        .isEmpty();
  }

  @Test
  void reasoningEffort_low_medium_high는_통과() {
    for (String v : List.of("low", "medium", "high")) {
      assertThat(OpencodeCredentialValidation.checkReasoningEffort(Map.of("reasoningEffort", v)))
          .as(v)
          .isEmpty();
    }
  }

  @Test
  void reasoningEffort_목록에_없으면_400() {
    Optional<Problem> problem =
        OpencodeCredentialValidation.checkReasoningEffort(Map.of("reasoningEffort", "extreme"));
    assertThat(problem).isPresent();
    assertThat(problem.get().status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void providerConsistency_ai_model이_비었으면_통과() {
    assertThat(OpencodeCredentialValidation.checkProviderConsistency("openai", null)).isEmpty();
    assertThat(OpencodeCredentialValidation.checkProviderConsistency("openai", "")).isEmpty();
  }

  /**
   * 슬래시가 없으면(예: {@code sdk} 시절의 Anthropic 모델 id) 대조 불가로 보고 통과시킨다 — 400 으로
   * 막지 않는다. 막으면 {@code sdk} 를 쓰던 테넌트가 opencode 로 처음 전환할 때, ai.model 이 아직
   * opencode 형식이 아니라는 이유만으로 opencode 자격증명 저장 자체가 막히는 순환 잠금이 생긴다
   * (ai.model 을 opencode 형식으로 바꾸려면 먼저 opencode 자격증명으로 모델을 프로브해야 하는데,
   * 그 저장이 막혀 있기 때문이다).
   */
  @Test
  void providerConsistency_슬래시가_없으면_통과() {
    assertThat(OpencodeCredentialValidation.checkProviderConsistency("openai", "claude-sonnet-4-20250514"))
        .isEmpty();
  }

  @Test
  void providerConsistency_provider가_다르면_400() {
    Optional<Problem> problem =
        OpencodeCredentialValidation.checkProviderConsistency("anthropic", "openai/gpt-4o");
    assertThat(problem).isPresent();
    assertThat(problem.get().status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void providerConsistency_provider가_같으면_통과() {
    assertThat(OpencodeCredentialValidation.checkProviderConsistency("openai", "openai/gpt-4o")).isEmpty();
  }

  @Test
  void modelMembership_목록이_비면_통과() {
    // 공급자가 /models 를 안 주면 화면이 자유 입력으로 전환되므로 막지 않는다(스펙).
    assertThat(OpencodeCredentialValidation.checkModelMembership("openai/no-such-model", List.of()))
        .isEmpty();
  }

  @Test
  void modelMembership_목록에_있으면_통과() {
    assertThat(
            OpencodeCredentialValidation.checkModelMembership(
                "openai/gpt-4o", List.of("gpt-4o", "gpt-4o-mini")))
        .isEmpty();
  }

  @Test
  void modelMembership_목록에_없으면_422() {
    Optional<Problem> problem =
        OpencodeCredentialValidation.checkModelMembership("openai/no-such-model", List.of("gpt-4o"));
    assertThat(problem).isPresent();
    assertThat(problem.get().status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void modelMembership_ai_model이_비었으면_통과() {
    assertThat(OpencodeCredentialValidation.checkModelMembership(null, List.of("gpt-4o"))).isEmpty();
  }
}
