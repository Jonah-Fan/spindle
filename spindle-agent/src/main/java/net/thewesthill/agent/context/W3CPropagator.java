package net.thewesthill.agent.context;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C Trace Context propagator.
 * <p>
 * Responsible for generating Trace IDs and Span IDs compliant with the W3C trace context
 * specification, as well as parsing incoming W3C traceparent header strings.
 * <p>
 * Generated IDs are hexadecimal strings:
 * <ul>
 *   <li>Trace ID: 32 hex characters (16 bytes)</li>
 *   <li>Span ID: 16 hex characters (8 bytes)</li>
 * </ul>
 * <p>
 * Specification reference:
 * <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class W3CPropagator {

  /**
   * Byte length of a Trace ID (16 bytes = 128 bits).
   */
  private static final int TRACE_ID_BYTES = 16;

  /**
   * Byte length of a Span ID (8 bytes = 64 bits).
   */
  private static final int SPAN_ID_BYTES = 8;

  /**
   * Hex character length of a Trace ID (32).
   */
  private static final int TRACE_ID_HEX_LEN = TRACE_ID_BYTES * 2;

  /**
   * Hex character length of a Span ID (16).
   */
  private static final int SPAN_ID_HEX_LEN = SPAN_ID_BYTES * 2;

  /**
   * W3C traceparent version, fixed to {@code "00"}.
   */
  private static final String VERSION = "00";

  /**
   * W3C traceparent flags, fixed to {@code "01"} (sampled).
   */
  private static final String FLAGS = "01";

  /**
   * Hex character lookup table; index 0-15 maps to {@code '0'-'9'}, {@code 'a'-'f'}.
   */
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /**
   * Private constructor to prevent instantiation.
   */
  private W3CPropagator() {
  }

  /**
   * Generates a new W3C Trace ID.
   * <p>
   * The Trace ID is a 32-character hex string (16 bytes) and will never be all zeros (all-zero IDs
   * are considered invalid).
   *
   * @return a 32-character hexadecimal Trace ID
   */
  public static String generateTraceId() {
    return generateHexId(TRACE_ID_BYTES);
  }

  /**
   * Generates a new W3C Span ID.
   * <p>
   * The Span ID is a 16-character hex string (8 bytes) and will never be all zeros (all-zero IDs
   * are considered invalid).
   *
   * @return a 16-character hexadecimal Span ID
   */
  public static String generateSpanId() {
    return generateHexId(SPAN_ID_BYTES);
  }

  /**
   * Generates a hexadecimal ID string of the specified byte length.
   * <p>
   * Uses {@link ThreadLocalRandom} to produce random bytes. If the result is all zeros, the first
   * character is forced to {@code '1'} to avoid an invalid ID.
   *
   * @param byteCount the number of bytes to generate
   * @return a hex string of length {@code byteCount * 2}
   */
  private static String generateHexId(int byteCount) {
    char[] buf = new char[byteCount * 2];
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    for (int i = 0; i < byteCount; i++) {
      int b = rnd.nextInt(256);
      buf[i * 2] = HEX[b >>> 4];
      buf[i * 2 + 1] = HEX[b & 0xF];
    }
    if (isAllZero(buf)) {
      buf[0] = '1';
    }
    return new String(buf);
  }

  /**
   * Parses a W3C traceparent header string and extracts child context information.
   * <p>
   * Expected header format:
   * <pre>00-&lt;32-char traceId&gt;-&lt;16-char parentSpanId&gt;-01</pre>
   * Total length must be at least 55 characters.
   * <p>
   * Returns {@code null} if the format is invalid or the IDs are malformed.
   *
   * @param header the W3C traceparent header string, e.g.
   *               {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}
   * @return a parsed {@link TraceContext}, or {@code null} if invalid
   */
  public static TraceContext parseChild(String header) {
    if (header == null || header.length() < 55) {
      return null;
    }

    int firstDash = header.indexOf('-');
    int secondDash = header.indexOf('-', firstDash + 1);
    int thirdDash = header.indexOf('-', secondDash + 1);

    if (firstDash != 2 || secondDash != 35 || thirdDash != 52) {
      return null;
    }

    if (!header.startsWith(VERSION) || !header.endsWith("-" + FLAGS)) {
      return null;
    }

    String traceId = header.substring(firstDash + 1, secondDash);
    String parentSpanId = header.substring(secondDash + 1, thirdDash);

    if (isInValidHexId(traceId, TRACE_ID_HEX_LEN) || isInValidHexId(parentSpanId,
        SPAN_ID_HEX_LEN)) {
      return null;
    }

    return new TraceContext(traceId, generateSpanId(), parentSpanId);
  }

  /**
   * Checks whether the given hex string is an <strong>invalid</strong> ID.
   * <p>
   * An ID is invalid if its length does not match, it contains non-hex characters, or it consists
   * entirely of {@code '0'}.
   *
   * @param s           the string to check
   * @param expectedLen the expected character length
   * @return {@code true} if the ID is invalid; {@code false} if valid
   */
  public static boolean isInValidHexId(String s, int expectedLen) {
    return !isValidHexId(s, expectedLen);
  }

  /**
   * Checks whether the given hex string is a <strong>valid</strong> ID.
   * <p>
   * Validity conditions:
   * <ul>
   *   <li>Length must exactly equal {@code expectedLen}</li>
   *   <li>Characters must be in {@code 0-9} or {@code a-f} only</li>
   *   <li>Must not be all {@code '0'} (all-zero IDs are invalid)</li>
   * </ul>
   *
   * @param s           the string to check
   * @param expectedLen the expected character length
   * @return {@code true} if the ID is valid; {@code false} if invalid
   */
  private static boolean isValidHexId(String s, int expectedLen) {
    if (s.length() != expectedLen) {
      return false;
    }
    boolean allZero = true;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || (c > '9' && c < 'a') || c > 'f') {
        return false;
      }
      if (c != '0') {
        allZero = false;
      }
    }
    return !allZero;
  }

  /**
   * Checks whether every character in the array is {@code '0'}.
   *
   * @param buf the character array to inspect
   * @return {@code true} if all characters are {@code '0'}
   */
  private static boolean isAllZero(char[] buf) {
    for (char c : buf) {
      if (c != '0') {
        return false;
      }
    }
    return true;
  }
}