package com.smartfirehub.pipeline.exception;

public class PipelineNotFoundException extends RuntimeException {
    public PipelineNotFoundException(String message) {
        super(message);
    }
}
