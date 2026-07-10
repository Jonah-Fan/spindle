package net.thewesthill.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentConfig#parse(String)}: defaults, recognized keys, case-insensitivity,
 * and graceful fallbacks for malformed values.
 */
class AgentConfigTest {

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("null argument yields all defaults")
    void nullArgs() {
      AgentConfig cfg = AgentConfig.parse(null);
      assertThat(cfg.dbPath()).isEqualTo("trace.db");
      assertThat(cfg.viewerPort()).isEqualTo(8787);
      assertThat(cfg.viewerEnabled()).isTrue();
      assertThat(cfg.configPath()).isNull();
      assertThat(cfg.logDir()).isEqualTo("./logs");
      assertThat(cfg.logLevel()).isEqualTo(Level.INFO);
      assertThat(cfg.logSize()).isEqualTo(50L * 1024 * 1024);
      assertThat(cfg.logCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("empty argument yields all defaults")
    void emptyArgs() {
      AgentConfig cfg = AgentConfig.parse("");
      assertThat(cfg.dbPath()).isEqualTo("trace.db");
      assertThat(cfg.viewerEnabled()).isTrue();
      assertThat(cfg.logLevel()).isEqualTo(Level.INFO);
    }
  }

  @Nested
  @DisplayName("recognized keys")
  class RecognizedKeys {

    @Test
    @DisplayName("viewer=off disables the viewer")
    void viewerOff() {
      assertThat(AgentConfig.parse("viewer=off").viewerEnabled()).isFalse();
    }

    @Test
    @DisplayName("viewer=OFF is case-insensitive")
    void viewerOffCaseInsensitive() {
      assertThat(AgentConfig.parse("viewer=OFF").viewerEnabled()).isFalse();
    }

    @Test
    @DisplayName("any other viewer value keeps it enabled")
    void viewerOtherKeepsEnabled() {
      assertThat(AgentConfig.parse("viewer=on").viewerEnabled()).isTrue();
    }

    @Test
    @DisplayName("all recognized keys parse their values")
    void allKeys() {
      AgentConfig cfg = AgentConfig.parse(
          "port=9999,db=/tmp/x.db,config=/tmp/c.yml,logDir=/var/log,logLevel=debug,logSize=1024,logCount=5");
      assertThat(cfg.viewerPort()).isEqualTo(9999);
      assertThat(cfg.dbPath()).isEqualTo("/tmp/x.db");
      assertThat(cfg.configPath()).isEqualTo("/tmp/c.yml");
      assertThat(cfg.logDir()).isEqualTo("/var/log");
      assertThat(cfg.logLevel()).isEqualTo(Level.FINE);
      assertThat(cfg.logSize()).isEqualTo(1024L);
      assertThat(cfg.logCount()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("malformed values fall back to defaults")
  class Malformed {

    @Test
    @DisplayName("non-numeric port falls back to 8787")
    void badPort() {
      assertThat(AgentConfig.parse("port=abc").viewerPort()).isEqualTo(8787);
    }

    @Test
    @DisplayName("non-numeric logSize falls back to 50 MB")
    void badLogSize() {
      assertThat(AgentConfig.parse("logSize=xyz").logSize()).isEqualTo(50L * 1024 * 1024);
    }

    @Test
    @DisplayName("non-numeric logCount falls back to 100")
    void badLogCount() {
      assertThat(AgentConfig.parse("logCount=notanumber").logCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("unknown keys are ignored")
    void unknownKey() {
      AgentConfig cfg = AgentConfig.parse("bogus=1,db=/tmp/y.db");
      assertThat(cfg.dbPath()).isEqualTo("/tmp/y.db");
    }
  }

  @Nested
  @DisplayName("logLevel mapping")
  class LogLevelMapping {

    @Test
    @DisplayName("warn -> WARNING")
    void warn() {
      assertThat(AgentConfig.parse("logLevel=warn").logLevel()).isEqualTo(Level.WARNING);
    }

    @Test
    @DisplayName("error -> SEVERE")
    void error() {
      assertThat(AgentConfig.parse("logLevel=error").logLevel()).isEqualTo(Level.SEVERE);
    }

    @Test
    @DisplayName("debug -> FINE")
    void debug() {
      assertThat(AgentConfig.parse("logLevel=debug").logLevel()).isEqualTo(Level.FINE);
    }

    @Test
    @DisplayName("unknown level -> INFO")
    void unknown() {
      assertThat(AgentConfig.parse("logLevel=verbose").logLevel()).isEqualTo(Level.INFO);
    }
  }
}
