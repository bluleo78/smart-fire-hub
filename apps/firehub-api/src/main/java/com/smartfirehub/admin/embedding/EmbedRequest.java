package com.smartfirehub.admin.embedding;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 내부 임베딩 실행 요청 — 벡터화할 텍스트 배치.
 *
 * <p>크기 상한(WD-600): 예전에는 개수·길이 제한이 없어 요청 한 번으로 임의 크기의 텍스트를 외부 임베딩 provider 로 흘려보낼 수 있었다(비용·지연 폭증).
 * 개수와 항목 길이를 아래 상수로 묶고, 넘으면 Bean Validation 이 400(한국어 문구)으로 거절한다. 원소 null 도 provider 에서 NPE 로 500 이
 * 되기 전에 400 으로 막는다. 빈 문자열은 허용한다(기존 동작 유지 — provider 가 처리).
 *
 * @param texts 벡터화할 텍스트 목록(필수, 최대 {@link #MAX_TEXTS}개, 각 최대 {@link #MAX_TEXT_LENGTH}자)
 */
public record EmbedRequest(
    @NotNull @Size(max = EmbedRequest.MAX_TEXTS, message = EmbedRequest.TOO_MANY_TEXTS_MESSAGE)
        List<
                @NotNull(message = EmbedRequest.NULL_TEXT_MESSAGE)
                @Size(
                    max = EmbedRequest.MAX_TEXT_LENGTH,
                    message = EmbedRequest.TEXT_TOO_LONG_MESSAGE)
                String>
            texts) {

  /**
   * 한 요청의 최대 텍스트 개수 — 256개. 유일한 호출자인 ai-agent GraphRAG 엔티티 해소가 이 개수씩 나눠 보낸다. 대응 상수: ai-agent {@code
   * EMBED_BATCH_SIZE}(apps/firehub-ai-agent/src/mcp/api-client/request-limits.ts) — 값을 바꾸면 그쪽도 함께
   * 바꾼다.
   */
  public static final int MAX_TEXTS = 256;

  /**
   * 항목당 최대 길이 — 2,000자(String.length, UTF-16 코드 단위). 용도가 엔티티 이름이라 넉넉하다. ai-agent 는 보내기 전에 이 길이로
   * 자르므로(긴 이름 하나로 ingest 전체가 실패하지 않게) 이 400 은 다른 호출자의 과대 요청을 막는 방어선이다. 대응 상수: ai-agent {@code
   * EMBED_MAX_TEXT_LENGTH}(apps/firehub-ai-agent/src/mcp/api-client/request-limits.ts) — 값을 바꾸면 그쪽도
   * 함께 바꾼다.
   */
  public static final int MAX_TEXT_LENGTH = 2_000;

  /** 개수 초과 안내 — 어노테이션 속성이라 컴파일 시간 상수여야 한다(숫자를 바꾸면 문구도 바꾼다). */
  public static final String TOO_MANY_TEXTS_MESSAGE = "한 번에 임베딩할 수 있는 텍스트는 최대 256개입니다.";

  /** 항목 길이 초과 안내. */
  public static final String TEXT_TOO_LONG_MESSAGE = "임베딩할 텍스트는 항목당 최대 2,000자까지 보낼 수 있습니다.";

  /** null 원소 안내. */
  public static final String NULL_TEXT_MESSAGE = "임베딩할 텍스트 항목은 비어 있을 수 없습니다.";
}
