package com.smartfirehub.dataimport.dto;

import java.util.List;

public record ImportValidateResponse(
    int totalRows, int validRows, int errorRows, List<ValidationErrorDetail> errors) {}
