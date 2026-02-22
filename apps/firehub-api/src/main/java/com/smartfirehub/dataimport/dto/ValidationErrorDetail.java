package com.smartfirehub.dataimport.dto;

public record ValidationErrorDetail(int rowNumber, String columnName, String value, String error) {}
