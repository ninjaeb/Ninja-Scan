package com.eugeneboon.docscanner.util

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a minimal but valid .xlsx workbook (a zip of OOXML parts) with a
 * single sheet of inline strings — no spreadsheet library needed.
 */
object XlsxWriter {

    fun write(
        output: OutputStream,
        sheetName: String,
        headers: List<String>,
        rows: List<List<String>>,
    ) {
        ZipOutputStream(output).use { zip ->
            fun part(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", workbookXml(sheetName))
            part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            part("xl/worksheets/sheet1.xml", sheetXml(listOf(headers) + rows))
        }
    }

    private fun sheetXml(rows: List<List<String>>): String = buildString {
        append(XML_HEADER)
        append("<worksheet xmlns=\"$MAIN_NS\"><sheetData>")
        for (row in rows) {
            append("<row>")
            for (cell in row) {
                append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(escape(cell))
                append("</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun workbookXml(sheetName: String): String =
        XML_HEADER +
            "<workbook xmlns=\"$MAIN_NS\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"${escape(sheetName.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/>" +
            "</sheets></workbook>"

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val XML_HEADER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val MAIN_NS =
        "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private const val CONTENT_TYPES = XML_HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" " +
        "ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd." +
        "openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd." +
        "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/" +
        "2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/" +
        "2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "</Relationships>"
}
