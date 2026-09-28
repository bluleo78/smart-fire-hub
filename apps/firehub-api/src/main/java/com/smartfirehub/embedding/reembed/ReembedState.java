package com.smartfirehub.embedding.reembed;

import java.time.OffsetDateTime;

/** embedding_reembed_state 한 행. status: IDLE | RUNNING | DONE | FAILED | SUPERSEDED. */
public record ReembedState(
    String status, String model, Integer dimension, String lastError, OffsetDateTime updatedAt) {}
