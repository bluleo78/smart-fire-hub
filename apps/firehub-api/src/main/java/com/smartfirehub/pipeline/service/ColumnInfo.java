package com.smartfirehub.pipeline.service;

/** Represents a column name and its inferred application-level data type. */
public record ColumnInfo(String name, String appType) {}
