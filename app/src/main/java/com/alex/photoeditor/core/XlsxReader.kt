package com.alex.photoeditor.core

import org.xml.sax.InputSource
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class XlsxSheet(val name: String, val rows: List<List<String>>)
data class XlsxDocument(
    val sheets: List<XlsxSheet>,
    val error: String? = null
)

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

    fun read(path: String): XlsxDocument = runCatching {
        ZipFile(path).use { zip ->
            fun entry(name: String): String? = zip.getEntry(name)?.let { e ->
                zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val shared = elementsNamed(parse(entry("xl/sharedStrings.xml").orEmpty()), "si")
                .map { it.textContent.orEmpty() }

            val rels = elementsNamed(parse(entry("xl/_rels/workbook.xml.rels").orEmpty()), "Relationship")
                .mapNotNull { node ->
                    val id = node.attributes?.getNamedItem("Id")?.nodeValue ?: return@mapNotNull null
                    val target = node.attributes?.getNamedItem("Target")?.nodeValue
                        ?: return@mapNotNull null
                    if (target.contains("worksheet", ignoreCase = true)) {
                        val resolved = when {
                            target.startsWith("/") -> target.removePrefix("/")
                            target.startsWith("xl/") -> target
                            else -> "xl/$target"
                        }
                        id to resolved
                    } else null
                }.toMap()

            val workbook = parse(entry("xl/workbook.xml").orEmpty())
            var sheets = emptyList<XlsxSheet>()
            if (workbook != null) {
                val sheetNodes = elementsNamed(workbook, "sheet")
                sheets = (0 until minOf(sheetNodes.size, MAX_SHEETS)).mapNotNull { i ->
                    val node = sheetNodes[i]
                    val name = node.attributes?.getNamedItem("name")?.nodeValue ?: "Sheet ${i + 1}"
                    val id = (0 until (node.attributes?.length ?: 0)).mapNotNull { a ->
                        val attr = node.attributes?.item(a) ?: return@mapNotNull null
                        if (attr.localName == "id" || attr.nodeName.endsWith(":id")) {
                            attr.nodeValue
                        } else null
                    }.firstOrNull()
                    val part = id?.let { rels[it] } ?: "xl/worksheets/sheet${i + 1}.xml"
                    val xml = entry(part) ?: return@mapNotNull null
                    XlsxSheet(name, parseSheet(xml, shared))
                }
            }
            if (sheets.isEmpty()) {
                // Fallback for workbooks whose rels/workbook parts are unusual:
                // try the conventional sheet paths directly.
                sheets = (1..MAX_SHEETS).mapNotNull { i ->
                    val xml = entry("xl/worksheets/sheet$i.xml") ?: return@mapNotNull null
                    XlsxSheet("Sheet $i", parseSheet(xml, shared))
                }
            }
            if (sheets.isEmpty()) {
                XlsxDocument(emptyList(), "This XLSX has no readable sheets.")
            } else {
                XlsxDocument(sheets)
            }
        }
    }.getOrElse { error ->
        XlsxDocument(emptyList(), error.message ?: "Could not read this XLSX file.")
    }

    /** Matches elements by local name, with or without an XML namespace prefix. */
    private fun elementsNamed(node: org.w3c.dom.Node?, name: String): List<org.w3c.dom.Node> {
        if (node == null) return emptyList()
        fun local(n: org.w3c.dom.Node): String =
            n.localName ?: n.nodeName.substringAfterLast(':')
        fun collect(n: org.w3c.dom.Node, out: ArrayList<org.w3c.dom.Node>) {
            val children = n.childNodes ?: return
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    if (local(child).equals(name, ignoreCase = true)) out.add(child)
                    collect(child, out)
                }
            }
        }
        val result = ArrayList<org.w3c.dom.Node>()
        collect(node, result)
        return result
    }

    private fun textOf(cell: org.w3c.dom.Node, tag: String): String =
        elementsNamed(cell, tag).joinToString("") { it.textContent.orEmpty() }.trim()

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val doc = parse(xml) ?: return emptyList()
        val rows = elementsNamed(doc, "row")
        val result = (0 until minOf(rows.size, MAX_ROWS)).map { i ->
            val cells = MutableList(MAX_COLS) { "" }
            elementsNamed(rows[i], "c").forEach { cell ->
                val ref = cell.attributes?.getNamedItem("r")?.nodeValue.orEmpty()
                val col = columnIndex(ref) ?: return@forEach
                if (col >= MAX_COLS) return@forEach
                val type = cell.attributes?.getNamedItem("t")?.nodeValue.orEmpty()
                val raw = textOf(cell, "v")
                cells[col] = when (type) {
                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> textOf(cell, "is") + textOf(cell, "t")
                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                    "str" -> raw
                    "e" -> raw
                    else -> raw
                }
            }
            cells
        }
        if (result.any { row -> row.any { it.isNotBlank() } }) return result

        // Fallback for sheets whose cells use an unexpected structure: lay the
        // raw <t>/<v> values out sequentially so their content is visible.
        val values = (elementsNamed(doc, "t") + elementsNamed(doc, "v"))
            .map { it.textContent.orEmpty() }
            .filter { it.isNotBlank() }
            .take(MAX_ROWS * MAX_COLS)
        if (values.isEmpty()) return result
        return values.chunked(MAX_COLS).take(MAX_ROWS).map { chunk ->
            (chunk + List(MAX_COLS - chunk.size) { "" }).toMutableList()
        }
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
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull()
    }
}
