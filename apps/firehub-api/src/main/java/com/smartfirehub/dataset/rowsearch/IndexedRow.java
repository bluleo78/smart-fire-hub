package com.smartfirehub.dataset.rowsearch;

/**
 * 색인에 쓸 행 한 건. embedding 은 provider 차원과 같아야 한다. 키워드 전용 색인(S3 — 등급이 임베딩 공급자를 허용하지 않음)에서는 null 이고
 * embeddingModel 은 {@link RowSearchSyncService#KEYWORD_ONLY_MODEL} 이다.
 */
public record IndexedRow(
    long rowId, String sourceText, String sourceHash, float[] embedding, String embeddingModel) {}
