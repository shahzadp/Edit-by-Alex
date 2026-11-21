package com.alex.photoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    @Test
    fun reads_prefixed_and_plain_xlsx() {
        val file = File.createTempFile("xlsx_reader_test", ".xlsx")
        val shared = """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Hello</t></si></sst>"""
        val workbook = """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
        val sheet = """<?xml version="1.0"?><x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:sheetData><x:row r="1"><x:c r="A1" t="s"><x:v>0</x:v></x:c><x:c r="B1"><x:v>42</x:v></x:c></x:row></x:sheetData></x:worksheet>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("xl/sharedStrings.xml", shared)
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", rels)
            put("xl/worksheets/sheet1.xml", sheet)
        }
        val doc = XlsxReader.read(file.absolutePath)
        assertTrue("sheets=${doc.sheets.size} error=${doc.error}", doc.sheets.size == 1)
        assertEquals("Data", doc.sheets[0].name)
        println("ROWS=${doc.sheets[0].rows.map { it.take(3) }}")
        assertEquals("Hello", doc.sheets[0].rows[0][0])
        assertEquals("42", doc.sheets[0].rows[0][1])
        file.delete()
    }
}
