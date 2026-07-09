package net.thewesthill.agent.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fluent builder that constructs an HTML document by appending tags to an internal buffer.
 * <p>
 * Tracks open tags on a stack so that
 * {@link #endTag}/{@link #endTable}/{@link #endBody}/{@link #build} can close them in reverse
 * order. Methods are chainable (each returns {@code this}) and escape text content where
 * appropriate. Used by {@link HtmlRenderer} to render the viewer pages.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class HtmlBuilder {

  /**
   * Accumulating document buffer, pre-sized to 8 KB.
   */
  private final StringBuilder sb = new StringBuilder(8192);
  /**
   * Stack of open element names awaiting their closing tag.
   */
  private final Deque<String> openTags = new ArrayDeque<>();

  /**
   * HTML-escapes a string for safe text content, returning the empty string for {@code null}.
   * Escapes {@code &}, {@code <}, and {@code >}.
   *
   * @param s the string to escape, or {@code null}
   * @return the escaped string, or {@code ""} if {@code s} was {@code null}
   */
  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  /**
   * Emits the document head, doctype, charset meta, and opening {@code <html>} and {@code <head>}
   * tags with the given (escaped) title.
   *
   * @param t the page title
   * @return this builder
   */
  public HtmlBuilder title(String t) {
    sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
    sb.append("<title>").append(escape(t)).append("</title>");
    return this;
  }

  /**
   * Emits a {@code <style>} block containing the given CSS verbatim.
   *
   * @param css the stylesheet source
   * @return this builder
   */
  public HtmlBuilder style(String css) {
    sb.append("<style>").append(css).append("</style>");
    return this;
  }

  /**
   * Closes {@code <head>} and opens {@code <body>}.
   *
   * @return this builder
   */
  public HtmlBuilder body() {
    sb.append("</head><body>");
    return this;
  }

  /**
   * Emits an {@code <h1>} with the given (escaped) text.
   *
   * @param text the heading text
   * @return this builder
   */
  public HtmlBuilder h1(String text) {
    sb.append("<h1>").append(escape(text)).append("</h1>");
    return this;
  }

  /**
   * Emits an element with an optional class. When {@code content} is non-null the element is
   * emitted self-contained (open + content + close); otherwise only the opening tag is emitted and
   * the tag name is pushed onto the open-tag stack for a later {@link #endTag}.
   *
   * @param tag     the element name
   * @param clazz   the {@code class} attribute value, or {@code null} for none
   * @param content the element's text content, or {@code null} to leave the tag open
   * @return this builder
   */
  public HtmlBuilder tag(String tag, String clazz, String content) {
    sb.append("<").append(tag);
    if (clazz != null) {
      sb.append(" class=\"").append(clazz).append("\"");
    }
    sb.append(">");
    if (content != null) {
      sb.append(content).append("</").append(tag).append(">");
    } else {
      openTags.push(tag);
    }
    return this;
  }

  /**
   * Emits an {@code <a>} link with an optional class and verbatim (unescaped) text.
   *
   * @param href  the link target
   * @param clazz the {@code class} attribute value, or {@code null} for none
   * @param text  the link label, emitted verbatim
   * @return this builder
   */
  @SuppressWarnings("SameParameterValue")
  public HtmlBuilder link(String href, String clazz, String text) {
    sb.append("<a");
    if (clazz != null) {
      sb.append(" class=\"").append(clazz).append("\"");
    }
    sb.append(" href=\"").append(href).append("\">").append(text).append("</a>");
    return this;
  }

  /**
   * Closes the most recently opened tag (the top of the open-tag stack). A no-op when the stack is
   * empty.
   *
   * @return this builder
   */
  public HtmlBuilder endTag() {
    String t = openTags.poll();
    if (t != null) {
      sb.append("</").append(t).append(">");
    }
    return this;
  }

  /**
   * Opens a {@code <table>} and pushes {@code "table"} onto the open-tag stack.
   *
   * @return this builder
   */
  public HtmlBuilder table() {
    sb.append("<table>");
    openTags.push("table");
    return this;
  }

  /**
   * Emits a {@code <thead>} row with one escaped {@code <th>} per column name.
   *
   * @param cols the column header texts
   * @return this builder
   */
  @SuppressWarnings("SameParameterValue")
  public HtmlBuilder thead(String... cols) {
    sb.append("<thead><tr>");
    for (String c : cols) {
      sb.append("<th>").append(escape(c)).append("</th>");
    }
    sb.append("</tr></thead>");
    return this;
  }

  /**
   * Emits a {@code <tbody>} wrapping the rows built by the given consumer against a fresh
   * {@link TableRowBuilder}.
   *
   * @param consumer builds the rows into the supplied row builder
   * @return this builder
   */
  public HtmlBuilder tbody(Consumer<TableRowBuilder> consumer) {
    TableRowBuilder builder = new TableRowBuilder();
    consumer.accept(builder);
    sb.append("<tbody>").append(builder.build()).append("</tbody>");
    return this;
  }

  /**
   * Closes the current table: pops and closes every open tag down to and including the nearest
   * {@code "table"}. A no-op when no table is open.
   *
   * @return this builder
   */
  public HtmlBuilder endTable() {
    while (!openTags.isEmpty() && !"table".equals(openTags.peek())) {
      sb.append("</").append(openTags.poll()).append(">");
    }
    if ("table".equals(openTags.peek())) {
      openTags.poll();
      sb.append("</table>");
    }
    return this;
  }

  /**
   * Closes every remaining open tag (the body content), leaving {@code </body></html>} still
   * pending.
   *
   * @return this builder
   */
  public HtmlBuilder endBody() {
    while (!openTags.isEmpty()) {
      sb.append("</").append(openTags.poll()).append(">");
    }
    return this;
  }

  /**
   * Iterates {@code items}, invoking the consumer once per item with this builder.
   *
   * @param items    the items to iterate
   * @param consumer receives this builder and each item
   * @param <T>      the item type
   * @return this builder
   */
  public <T> HtmlBuilder each(List<T> items, BiConsumer<HtmlBuilder, T> consumer) {
    for (T item : items) {
      consumer.accept(this, item);
    }
    return this;
  }

  /**
   * Closes any remaining open tags and appends {@code </body></html>}, returning the finished
   * document.
   *
   * @return the complete HTML document as a string
   */
  public String build() {
    while (!openTags.isEmpty()) {
      sb.append("</").append(openTags.poll()).append(">");
    }
    return sb.append("</body></html>").toString();
  }

  /**
   * Fluent builder for table rows, accumulating {@code <tr>} cells into a separate buffer that
   * {@link HtmlBuilder#tbody} wraps in a {@code <tbody>}.
   */
  public static class TableRowBuilder {

    /**
     * Buffer accumulating this builder's row HTML.
     */
    private final StringBuilder rows = new StringBuilder();

    /**
     * Opens a {@code <tr>}.
     *
     * @return this builder
     */
    public TableRowBuilder tr() {
      rows.append("<tr>");
      return this;
    }

    /**
     * Emits a {@code <td>} containing the given (escaped) text.
     *
     * @param content the cell text, or {@code null}
     * @return this builder
     */
    public TableRowBuilder td(String content) {
      rows.append("<td>").append(escape(content)).append("</td>");
      return this;
    }

    /**
     * Emits a {@code <td>} containing the given HTML verbatim (unescaped).
     *
     * @param html the raw cell HTML
     * @return this builder
     */
    public TableRowBuilder tdRaw(String html) {
      rows.append("<td>").append(html).append("</td>");
      return this;
    }

    /**
     * Emits a {@code <td>} containing an {@code <a>} link with escaped label text.
     *
     * @param href the link target
     * @param text the link label (escaped)
     * @return this builder
     */
    public TableRowBuilder tdLink(String href, String text) {
      rows.append("<td><a href=\"").append(href).append("\">")
          .append(escape(text)).append("</a></td>");
      return this;
    }

    /**
     * Closes the current {@code <tr>}.
     *
     * @return this builder
     */
    @SuppressWarnings("UnusedReturnValue")
    public TableRowBuilder endTr() {
      rows.append("</tr>");
      return this;
    }

    /**
     * Returns the accumulated row HTML.
     *
     * @return the row markup as a string
     */
    public String build() {
      return rows.toString();
    }
  }
}
