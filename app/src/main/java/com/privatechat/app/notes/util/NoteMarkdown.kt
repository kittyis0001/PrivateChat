package com.privatechat.app.notes.util

/**
 * Renders a note's stored text as a small self-contained HTML page
 * for the editor Preview (a WebView). The note itself stays plain
 * text — **bold** / __underline__ markers and | pipe | tables —
 * because that is what Scan text and the AI tools produce, and the
 * editor is a plain EditText that cannot draw a table. The Preview
 * is the reading view: it draws the table with real borders, bold
 * and underline as the book printed them, so a scanned page looks
 * like the page instead of raw pipe text with <br> tags in it.
 *
 * Pure text in, HTML out — no Android calls, so nothing here can
 * touch a note, a file or the network. Only the small subset the
 * app itself produces is supported: pipe tables, **bold**,
 * __underline__, **__both__**, whole-line bold headings, and
 * paragraphs. Anything else is shown as plain text.
 */
object NoteMarkdown {

    /** Placeholder for a <br> tag found in stored text (old scans). */
    private const val BR = "\u0000BR\u0000"

    fun toHtml(title: String, content: String, backgroundHex: String, textHex: String): String {
        val body = StringBuilder()
        val t = title.trim()
        if (t.isNotEmpty()) {
            body.append("<h1>").append(inline(t)).append("</h1>\n")
        }
        body.append(renderBlocks(content))
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>" +
            "body{font-family:sans-serif;color:$textHex;background:$backgroundHex;" +
            "margin:0;padding:16px;font-size:16px;line-height:1.55;}" +
            "h1{font-size:22px;margin:0 0 12px 0;}" +
            "h2{font-size:18px;margin:14px 0 6px 0;}" +
            "p{margin:0 0 10px 0;}" +
            "table{border-collapse:collapse;width:100%;margin:8px 0 12px 0;}" +
            "th,td{border:1px solid #9a8fc0;padding:6px 8px;text-align:left;vertical-align:top;}" +
            "th{background:#e9e2f7;}" +
            "td{background:#ffffff;}" +
            "</style></head><body>" + body + "</body></html>"
    }

    private fun renderBlocks(content: String): String {
        val out = StringBuilder()
        val lines = content.split('\n')
        var i = 0
        val para = mutableListOf<String>()
        fun flushPara() {
            if (para.isEmpty()) return
            out.append("<p>")
            out.append(para.joinToString("<br>") { inline(it) })
            out.append("</p>\n")
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                flushPara()
                i++
                continue
            }
            if (line.startsWith("|")) {
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(lines[i].trim())
                    i++
                }
                flushPara()
                out.append(renderTable(rows))
                continue
            }
            if (isWholeLineBold(line)) {
                flushPara()
                out.append("<h2>").append(inline(line.removePrefix("**").removeSuffix("**")))
                    .append("</h2>\n")
                i++
                continue
            }
            para.add(lines[i].trimEnd())
            i++
        }
        flushPara()
        return out.toString()
    }

    private fun isWholeLineBold(line: String): Boolean {
        return line.length > 4 && line.startsWith("**") && line.endsWith("**") &&
            !line.contains("|")
    }

    private fun renderTable(rows: List<String>): String {
        if (rows.isEmpty()) return ""
        // Split a pipe row into cells, dropping the empty ends the
        // leading and trailing pipes produce.
        fun cells(row: String): List<String> {
            val parts = row.split("|")
            val trimmed = parts.map { it.trim() }
            val start = if (trimmed.firstOrNull().isNullOrEmpty()) 1 else 0
            val end = if (trimmed.lastOrNull().isNullOrEmpty()) trimmed.size - 1 else trimmed.size
            if (start >= end) return emptyList()
            return trimmed.subList(start, end)
        }
        // The second row of a Markdown table is the |---|---| divider,
        // and a scan sometimes leaves an empty |||| row above it.
        // Neither is a real row: drop divider rows and rows whose
        // cells are all empty.
        val bodyRows = rows.filterNot { row ->
            val divider = row.replace("|", "").replace("-", "").replace(" ", "").isEmpty() &&
                row.contains("-")
            divider || cells(row).all { it.isEmpty() }
        }
        if (bodyRows.isEmpty()) return ""
        val out = StringBuilder("<table>\n")
        val header = cells(bodyRows[0])
        if (header.isNotEmpty()) {
            out.append("<tr>")
            header.forEach { out.append("<th>").append(inline(it)).append("</th>") }
            out.append("</tr>\n")
        }
        bodyRows.drop(1).forEach { row ->
            val cs = cells(row)
            if (cs.isEmpty()) return@forEach
            out.append("<tr>")
            cs.forEach { out.append("<td>").append(inline(it)).append("</td>") }
            out.append("</tr>\n")
        }
        out.append("</table>\n")
        return out.toString()
    }

    /**
     * Bold / underline markers to HTML. A <br> stored in the text
     * (older scans put them inside table cells) becomes a real line
     * break; anything else that looks like a tag is escaped, so a
     * scanned "<" can never break the page.
     */
    private fun inline(text: String): String {
        var s = text.replace(Regex("(?i)<br\\s*/?>"), BR)
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        s = s.replace(Regex("\\*\\*__(.+?)__\\*\\*"), "<b><u>$1</u></b>")
        s = s.replace(Regex("__\\*\\*(.+?)\\*\\*__"), "<b><u>$1</u></b>")
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        s = s.replace(Regex("__(.+?)__"), "<u>$1</u>")
        return s.replace(BR, "<br>")
    }
}
