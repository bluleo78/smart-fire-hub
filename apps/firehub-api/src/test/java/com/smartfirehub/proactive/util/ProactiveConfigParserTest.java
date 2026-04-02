package com.smartfirehub.proactive.util;

import static org.assertj.core.api.Assertions.*;

import com.smartfirehub.proactive.util.ProactiveConfigParser.ChannelConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProactiveConfigParserTest {

  // ---------------------------------------------------------------------------
  // parseChannels - old format
  // ---------------------------------------------------------------------------

  @Test
  void parseOldFormat_channelsAsStringArray() {
    Map<String, Object> config = Map.of("channels", List.of("CHAT", "EMAIL"), "targets", "ALL");
    List<ChannelConfig> result = ProactiveConfigParser.parseChannels(config);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).type()).isEqualTo("CHAT");
    assertThat(result.get(0).recipientUserIds()).isEmpty();
    assertThat(result.get(0).recipientEmails()).isEmpty();
    assertThat(result.get(1).type()).isEqualTo("EMAIL");
  }

  @Test
  void parseOldFormat_singleChannel() {
    Map<String, Object> config = Map.of("channels", List.of("CHAT"));
    List<ChannelConfig> result = ProactiveConfigParser.parseChannels(config);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).type()).isEqualTo("CHAT");
  }

  // ---------------------------------------------------------------------------
  // parseChannels - new format
  // ---------------------------------------------------------------------------

  @Test
  void parseNewFormat_channelsAsObjectArray() {
    Map<String, Object> chatChannel =
        Map.of("type", "CHAT", "recipientUserIds", List.of(1, 2, 3), "recipientEmails", List.of());
    Map<String, Object> emailChannel =
        Map.of(
            "type",
            "EMAIL",
            "recipientUserIds",
            List.of(),
            "recipientEmails",
            List.of("a@b.com"));
    Map<String, Object> config = Map.of("channels", List.of(chatChannel, emailChannel));

    List<ChannelConfig> result = ProactiveConfigParser.parseChannels(config);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).type()).isEqualTo("CHAT");
    assertThat(result.get(0).recipientUserIds()).containsExactly(1L, 2L, 3L);
    assertThat(result.get(1).type()).isEqualTo("EMAIL");
    assertThat(result.get(1).recipientEmails()).containsExactly("a@b.com");
  }

  @Test
  void parseNewFormat_withoutOptionalFields() {
    Map<String, Object> chatChannel = new HashMap<>();
    chatChannel.put("type", "CHAT");
    // recipientUserIds and recipientEmails absent
    Map<String, Object> config = Map.of("channels", List.of(chatChannel));

    List<ChannelConfig> result = ProactiveConfigParser.parseChannels(config);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).type()).isEqualTo("CHAT");
    assertThat(result.get(0).recipientUserIds()).isEmpty();
    assertThat(result.get(0).recipientEmails()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // parseChannels - edge cases
  // ---------------------------------------------------------------------------

  @Test
  void parseNullConfig_returnsEmpty() {
    assertThat(ProactiveConfigParser.parseChannels(null)).isEmpty();
  }

  @Test
  void parseEmptyConfig_returnsEmpty() {
    assertThat(ProactiveConfigParser.parseChannels(Map.of())).isEmpty();
  }

  @Test
  void parseEmptyChannelsList_returnsEmpty() {
    Map<String, Object> config = Map.of("channels", List.of());
    assertThat(ProactiveConfigParser.parseChannels(config)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // getChannelConfig
  // ---------------------------------------------------------------------------

  @Test
  void getChannelConfig_found() {
    Map<String, Object> chatChannel =
        Map.of("type", "CHAT", "recipientUserIds", List.of(1), "recipientEmails", List.of());
    Map<String, Object> config = Map.of("channels", List.of(chatChannel));

    Optional<ChannelConfig> result = ProactiveConfigParser.getChannelConfig(config, "CHAT");

    assertThat(result).isPresent();
    assertThat(result.get().type()).isEqualTo("CHAT");
  }

  @Test
  void getChannelConfig_notFound() {
    Map<String, Object> config = Map.of("channels", List.of("CHAT"));

    Optional<ChannelConfig> result = ProactiveConfigParser.getChannelConfig(config, "EMAIL");

    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // getChannelTypes
  // ---------------------------------------------------------------------------

  @Test
  void getChannelTypes_newFormat_returnsTypeStrings() {
    Map<String, Object> chat =
        Map.of("type", "CHAT", "recipientUserIds", List.of(1), "recipientEmails", List.of());
    Map<String, Object> email =
        Map.of("type", "EMAIL", "recipientUserIds", List.of(), "recipientEmails", List.of("a@b.com"));
    Map<String, Object> config = Map.of("channels", List.of(chat, email));

    List<String> types = ProactiveConfigParser.getChannelTypes(config);

    assertThat(types).containsExactly("CHAT", "EMAIL");
  }

  @Test
  void getChannelTypes_oldFormat_returnsStrings() {
    Map<String, Object> config = Map.of("channels", List.of("CHAT", "EMAIL"));

    List<String> types = ProactiveConfigParser.getChannelTypes(config);

    assertThat(types).containsExactly("CHAT", "EMAIL");
  }

  // ---------------------------------------------------------------------------
  // getRecipientUserIds / getRecipientEmails
  // ---------------------------------------------------------------------------

  @Test
  void getRecipientUserIds_emptyArray_returnsEmpty() {
    ChannelConfig cfg = new ChannelConfig("CHAT", List.of(), List.of());
    assertThat(ProactiveConfigParser.getRecipientUserIds(cfg)).isEmpty();
  }

  @Test
  void getRecipientEmails_returnsEmails() {
    ChannelConfig cfg = new ChannelConfig("EMAIL", List.of(), List.of("a@b.com", "c@d.com"));
    assertThat(ProactiveConfigParser.getRecipientEmails(cfg)).containsExactly("a@b.com", "c@d.com");
  }

  // ---------------------------------------------------------------------------
  // validateEmails / validateEmail
  // ---------------------------------------------------------------------------

  @Test
  void validateEmails_validFormat_noException() {
    assertThatNoException()
        .isThrownBy(() -> ProactiveConfigParser.validateEmails(List.of("user@example.com", "a@b.co")));
  }

  @Test
  void validateEmails_invalidFormat_throws() {
    assertThatThrownBy(() -> ProactiveConfigParser.validateEmails(List.of("not-an-email")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not-an-email");
  }

  @Test
  void validateEmail_null_throws() {
    assertThatThrownBy(() -> ProactiveConfigParser.validateEmail(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
