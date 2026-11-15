package com.smartfirehub.dataset.rowsearch;

/**
 * 이전 세대 벡터 재사용을 시도할 행 하나(WD-743). 같은 원문 해시·같은 모델의 벡터가 이전 세대에 있으면 그 벡터로 지금 세대에 넣고, 없으면 호출자가 새로
 * 임베딩한다.
 */
public record ReuseCandidate(long rowId, String sourceText, String sourceHash) {}
