package com.smartfirehub.pipeline.dto;

/**
 * 파이프라인 삭제 영향(WD-170) — 삭제 확인 창이 「자동 생성된 임시 데이터셋 N개도 함께 삭제됩니다」를 보여 주는 데 쓴다.
 *
 * @param tempDatasetCount 파이프라인을 지우면 함께 지워질 자동 생성 임시 데이터셋 개수. 다른 파이프라인 스텝이 출력으로 쓰고 있어 남는 것은 세지
 *     않는다(실제 삭제와 같은 판정). 호출자가 볼 수 없는 것도 세지 않는다(WD-310 — 숨긴 개수를 드러내지 않게).
 * @param hasHiddenTempDatasets 호출자가 볼 수 없는 임시 데이터셋이 함께 지워질 대상에 있는가(WD-310). true 면 삭제가 403 이라 확인 창이
 *     삭제를 막고 안내한다.
 */
public record PipelineDeleteImpactResponse(int tempDatasetCount, boolean hasHiddenTempDatasets) {}
