package net.thewesthill.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link W3CPropagator}: ID generation and traceparent parsing per the W3C Trace
 * Context specification.
 */
class W3CPropagatorTest {

  private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
  private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");

  @Nested
  @DisplayName("generateTraceId / generateSpanId")
  class IdGeneration {

    @Test
    @DisplayName("trace id is 32 lowercase hex chars, never all zero")
    void traceIdFormat() {
      for (int i = 0; i < 100; i++) {
        String id = W3CPropagator.generateTraceId();
        assertThat(id).matches(HEX32);
        assertThat(id).isNotEqualTo("00000000000000000000000000000000");
      }
    }

    @Test
    @DisplayName("span id is 16 lowercase hex chars, never all zero")
    void spanIdFormat() {
      for (int i = 0; i < 100; i++) {
        String id = W3CPropagator.generateSpanId();
        assertThat(id).matches(HEX16);
        assertThat(id).isNotEqualTo("0000000000000000");
      }
    }
  }

  @Nested
  @DisplayName("parseChild")
  class ParseChild {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final String VALID =
        "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    @Test
    @DisplayName("valid header extracts traceId and parentSpanId, generates a new child spanId")
    void valid() {
      TraceContext ctx = W3CPropagator.parseChild(VALID);
      assertThat(ctx).isNotNull();
      assertThat(ctx.traceId()).isEqualTo(TRACE_ID);
      assertThat(ctx.parentId()).isEqualTo(PARENT_SPAN_ID);
      // the child span id is freshly generated, not the parent's
      assertThat(ctx.spanId()).matches(HEX16).isNotEqualTo(PARENT_SPAN_ID);
    }

    @Test
    @DisplayName("round-trip: generated traceparent preserves traceId and parent spanId")
    void roundTrip() {
      String traceId = W3CPropagator.generateTraceId();
      String spanId = W3CPropagator.generateSpanId();
      String traceparent = "00-" + traceId + "-" + spanId + "-01";

      TraceContext child = W3CPropagator.parseChild(traceparent);
      assertThat(child).isNotNull();
      assertThat(child.traceId()).isEqualTo(traceId);
      assertThat(child.parentId()).isEqualTo(spanId);
    }

    @Test
    @DisplayName("null header returns null")
    void nullHeader() {
      assertThat(W3CPropagator.parseChild(null)).isNull();
    }

    @Test
    @DisplayName("header shorter than 55 chars returns null")
    void tooShort() {
      assertThat(W3CPropagator.parseChild("00-x-01")).isNull();
    }

    @Test
    @DisplayName("wrong dash positions return null")
    void wrongDashPositions() {
      // 32-char traceId but dashes at the wrong places: "000-..."
      String bad = "000-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";
      assertThat(W3CPropagator.parseChild(bad)).isNull();
    }

    @Test
    @DisplayName("wrong version (not 00) returns null")
    void wrongVersion() {
      String bad = "01-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";
      assertThat(W3CPropagator.parseChild(bad)).isNull();
    }

    @Test
    @DisplayName("wrong flags (not -01) return null")
    void wrongFlags() {
      String bad = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-00";
      assertThat(W3CPropagator.parseChild(bad)).isNull();
    }

    @Test
    @DisplayName("all-zero traceId is invalid and returns null")
    void allZeroTraceId() {
      String bad = "00-00000000000000000000000000000000-" + PARENT_SPAN_ID + "-01";
      assertThat(W3CPropagator.parseChild(bad)).isNull();
    }

    @Test
    @DisplayName("non-hex characters in traceId return null")
    void nonHex() {
      String bad = "00-4bf92f3577b34da6a3ce929d0e0e473z-" + PARENT_SPAN_ID + "-01";
      assertThat(W3CPropagator.parseChild(bad)).isNull();
    }
  }

  @Nested
  @DisplayName("isInValidHexId")
  class IsValidHexId {

    @Test
    @DisplayName("valid 32-char non-zero hex id is valid")
    void validId() {
      assertThat(W3CPropagator.isInValidHexId(TRACE_ID_32(), 32)).isFalse();
    }

    @Test
    @DisplayName("all-zero id is invalid")
    void allZero() {
      assertThat(W3CPropagator.isInValidHexId("00000000000000000000000000000000", 32)).isTrue();
    }

    @Test
    @DisplayName("wrong length is invalid")
    void wrongLength() {
      assertThat(W3CPropagator.isInValidHexId("ab", 32)).isTrue();
    }

    @Test
    @DisplayName("uppercase hex is invalid (only a-f accepted)")
    void uppercase() {
      assertThat(W3CPropagator.isInValidHexId("ABCDEF0123456789ABCDEF0123456789", 32)).isTrue();
    }

    private static String TRACE_ID_32() {
      return "4bf92f3577b34da6a3ce929d0e0e4736";
    }
  }
}
