package com.smartfirehub.dataimport.dto;

import java.util.List;

public record ExportEstimate(
    long rowCount, boolean async, boolean hasGeometryColumn, List<ExportColumnInfo> columns) {}
