package com.smartfirehub.dataset.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 데이터셋 복제 요청.
 *
 * <p>이름 규칙은 화면 공용 이름 스키마({@code datasetNameSchema}, WD-523)와 같다(WD-699) — 앞뒤 공백을 자른 뒤 필수·100자 이하이고
 * 문구도 같다. 예전에는 {@code @NotBlank} 만 있어 화면을 거치지 않은 요청의 101자 이상 이름이 DB(dataset.name VARCHAR(100)) 오류로
 * 실패하고, 앞뒤 공백이 그대로 저장됐다. 길이는 {@code String.length()}(UTF-16 단위)로 센다 — 웹 zod {@code .max} 와 같은 단위이고,
 * UTF-16 100 이하면 코드 포인트로 세는 VARCHAR(100) 에도 늘 들어간다.
 *
 * @param name 새 데이터셋 이름 — 앞뒤 공백은 잘라 검증·저장한다. null 이면 「데이터셋 이름을 입력하세요」 400(서버가 기본 이름을 만들지 않는다)
 */
public record CloneDatasetRequest(
    @NotBlank(message = NAME_REQUIRED_MESSAGE)
        @Size(max = NAME_MAX_LENGTH, message = NAME_TOO_LONG_MESSAGE)
        String name,
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") String tableName,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.DATASET_TOO_LONG)
        String description,
    boolean includeData,
    boolean includeTags) {

  /** 데이터셋 이름 상한 — dataset.name VARCHAR(100)·웹 {@code DATASET_NAME_MAX_LENGTH} 와 같다. */
  public static final int NAME_MAX_LENGTH = 100;

  /** 이름이 비었을 때 문구 — 웹 {@code datasetNameSchema} 와 같은 문장이다. */
  public static final String NAME_REQUIRED_MESSAGE = "데이터셋 이름을 입력하세요";

  /** 이름이 상한을 넘을 때 문구 — 웹 {@code datasetNameSchema} 와 같은 문장이다. */
  public static final String NAME_TOO_LONG_MESSAGE = "이름은 100자 이하여야 합니다";

  /** 이름 앞뒤 공백은 잘라서 검증·저장한다 — 화면 trim 을 우회한 요청도 「 매출 」이 그대로 저장되지 않고 공백만이면 필수 오류가 되게(WD-699). */
  public CloneDatasetRequest {
    if (name != null) name = name.strip();
  }
}
