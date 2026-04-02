package com.smartfirehub.proactive.service.delivery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.IContext;

class EmailDeliveryChannelTest extends IntegrationTestBase {

  @Autowired private EmailDeliveryChannel emailDeliveryChannel;

  @MockitoBean private SettingsService settingsService;
  @MockitoBean private UserRepository userRepository;
  @MockitoBean private SpringTemplateEngine templateEngine;

  @BeforeEach
  void setup() {
    // Default: SMTP not configured -> deliver() returns early
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));
  }

  private ProactiveResult makeResult() {
    return new ProactiveResult("Test Report", List.of(), null);
  }

  private ProactiveJobResponse makeJob(Long userId, Map<String, Object> config) {
    return new ProactiveJobResponse(
        1L, userId, null, null, "Test Job", "prompt",
        "0 9 * * *", "Asia/Seoul", true, config,
        null, null, null, null, null);
  }

  private UserResponse makeUser(Long id, String name, String email) {
    return new UserResponse(id, "user" + id, email, name, true, null);
  }

  @Test
  void deliver_smtpNotConfigured_skips() {
    // smtp.host blank -> should return immediately without calling userRepository
    Map<String, Object> config = Map.of("channels", List.of("EMAIL"));
    ProactiveJobResponse job = makeJob(1L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    verify(userRepository, never()).findById(anyLong());
  }

  @Test
  void deliver_noRecipients_defaultsToJobOwner() {
    // SMTP configured, no recipientUserIds -> falls back to job owner
    when(settingsService.getSmtpConfig()).thenReturn(Map.of(
        "smtp.host", "smtp.example.com",
        "smtp.port", "587"));
    when(userRepository.findById(1L))
        .thenReturn(Optional.of(makeUser(1L, "Owner", "owner@example.com")));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config = Map.of(
        "channels", List.of(
            Map.of("type", "EMAIL",
                   "recipientUserIds", List.of(),
                   "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(1L, config);

    // Will try to send but fail (no real SMTP) — the exception is caught internally
    emailDeliveryChannel.deliver(job, 1L, makeResult());

    // Verify owner email was resolved
    verify(userRepository).findById(1L);
  }

  @Test
  void deliver_withRecipientUserIds_loadsEachUserEmail() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of(
        "smtp.host", "smtp.example.com",
        "smtp.port", "587"));
    when(userRepository.findById(10L))
        .thenReturn(Optional.of(makeUser(10L, "Alice", "alice@example.com")));
    when(userRepository.findById(20L))
        .thenReturn(Optional.of(makeUser(20L, "Bob", "bob@example.com")));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config = Map.of(
        "channels", List.of(
            Map.of("type", "EMAIL",
                   "recipientUserIds", List.of(10, 20),
                   "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    // Will try to send but SMTP will fail internally (caught)
    emailDeliveryChannel.deliver(job, 1L, makeResult());

    verify(userRepository).findById(10L);
    verify(userRepository).findById(20L);
    // owner should NOT be looked up (recipients specified)
    verify(userRepository, never()).findById(99L);
  }

  @Test
  void deliver_withRecipientEmails_usesExternalEmailsDirectly() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of(
        "smtp.host", "smtp.example.com",
        "smtp.port", "587"));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config = Map.of(
        "channels", List.of(
            Map.of("type", "EMAIL",
                   "recipientUserIds", List.of(),
                   "recipientEmails", List.of("external@example.com"))));
    ProactiveJobResponse job = makeJob(99L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    // No user repository calls needed for external emails
    verify(userRepository, never()).findById(anyLong());
  }

  @Test
  void deliver_withMixedRecipients_combinesBoth() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of(
        "smtp.host", "smtp.example.com",
        "smtp.port", "587"));
    when(userRepository.findById(10L))
        .thenReturn(Optional.of(makeUser(10L, "Alice", "alice@example.com")));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config = Map.of(
        "channels", List.of(
            Map.of("type", "EMAIL",
                   "recipientUserIds", List.of(10),
                   "recipientEmails", List.of("external@example.com"))));
    ProactiveJobResponse job = makeJob(99L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    // Registered user resolved
    verify(userRepository).findById(10L);
    // Owner not involved
    verify(userRepository, never()).findById(99L);
  }
}
