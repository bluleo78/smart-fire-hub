package com.smartfirehub.dataimport.dto;

public record ExportColumnInfo(
    String columnName, String displayName, String dataType, boolean isGeometry) {}
