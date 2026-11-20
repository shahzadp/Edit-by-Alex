package com.alex.photoeditor.core

import org.xml.sax.InputSource
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class XlsxSheet(val name: String, val rows: List<List<String>>)
data class XlsxDocument(val sheets: List<XlsxSheet>)

/**
 * Minimal read-only XLSX reader. XLSX is a ZIP of XML parts, both supported by
 * the platform, so no office engine or new dependency is needed for Tier 0
 * viewing. Reading is deliberately bounded (sheets/rows/columns) to keep the
 * viewer responsive and memory-safe on a phone.
 */
object XlsxReader {

    private const val MAX_SHEETS = 8
    private const val MAX_ROWS = 200
    private const val MAX_COLS = 30
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun read(path: String): XlsxDocument? = runCatching {
        ZipFile(path).use { zip ->
            fun entry(name: String): String? = zip.getEntry(name)?.let { e ->
                zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val shared = parse(entry("xl/sharedStrings.xml").orEmpty())
                ?.getElementsByTagName("si")
                ?.let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent.orEmpty() } }
                ?: emptyList()

            val rels = parse(entry("xl/_rels/workbook.xml.rels").orEmpty())
                ?.getElementsByTagName("Relationship")
                ?.let { nodes ->
                    (0 until nodes.length).mapNotNull { i ->
                        val node = nodes.item(i)
                        val id = node.attributes?.getNamedItem("Id")?.nodeValue ?: return@mapNotNull null
                        val target = node.attributes?.getNamedItem("Target")?.nodeValue
                            ?: return@mapNotNull null
                        val resolved = when {
                            target.startsWith("/") -> target.removePrefix("/")
                            target.startsWith("xl/") -> target
                            else -> "xl/$target"
                        }
                        id to resolved
                    }.toMap()
                } ?: emptyMap()

            val workbook = parse(entry("xl/workbook.xml").orEmpty())
                ?: return@use null
            val sheetNodes = workbook.getElementsByTagName("sheet")
            val sheets = (0 until minOf(sheetNodes.length, MAX_SHEETS)).mapNotNull { i ->
                val node = sheetNodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: "Sheet ${i + 1}"
                val id = node.attributes?.getNamedItemNS(REL_NS, "id")?.nodeValue
                    ?: node.attributes?.getNamedItem("r:id")?.nodeValue
                val part = id?.let { rels[it] } ?: return@mapNotNull null
                val xml = entry(part) ?: return@mapNotNull null
                XlsxSheet(name, parseSheet(xml, shared))
            }
            XlsxDocument(sheets)
        }
    }.getOrNull()

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val doc = parse(xml) ?: return emptyList()
        val rows = doc.getElementsByTagName("row")
        return (0 until minOf(rows.length, MAX_ROWS)).map { i ->
            val cells = MutableList(MAX_COLS) { "" }
            val row = rows.item(i)
            val cellNodes = row.childNodes
            for (j in 0 until cellNodes.length) {
                val cell = cellNodes.item(j)
                if (cell.nodeName != "c") continue
                val ref = cell.attributes?.getNamedItem("r")?.nodeValue.orEmpty()
                val col = columnIndex(ref) ?: continue
                if (col >= MAX_COLS) continue
                val type = cell.attributes?.getNamedItem("t")?.nodeValue.orEmpty()
                val raw = textOf(cell, "v")
                cells[col] = when (type) {
                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> textOf(cell, "is")
                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                    "e" -> raw
                    else -> raw
                }
            }
            cells
        }
    }

    private fun textOf(cell: org.w3c.dom.Node, tag: String): String {
        val nodes = (cell as? org.w3c.dom.Element)?.getElementsByTagName(tag)
            ?: return ""
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent.orEmpty() }.trim()
    }

    private fun columnIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        var value = 0
        letters.forEach { value = value * 26 + (it - 'A' + 1) }
        return value - 1
    }

    private fun parse(xml: String): org.w3c.dom.Document? {
        if (xml.isBlank()) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull()
    }
}
