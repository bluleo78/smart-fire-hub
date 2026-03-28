package com.smartfirehub.proactive.dto;

public record SmtpSettingsRequest(
    String host,
    String port,
    String username,
    String password,
    String starttls,
    String fromAddress) {}
