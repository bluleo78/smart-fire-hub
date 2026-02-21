package com.smartfirehub.pipeline.dto;

public enum LoadStrategy {
    REPLACE,  // Current default: TRUNCATE + INSERT
    APPEND    // INSERT without truncation
    // MERGE deferred — requires design spike for script table name redirection
}
