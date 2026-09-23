package com.smartfirehub.dataset.rowsearch;

/** 색인에 쓸 행 한 건. embedding 은 provider 차원과 같아야 한다. */
public record IndexedRow(
    long rowId, String sourceText, String sourceHash, float[] embedding, String embeddingModel) {}
