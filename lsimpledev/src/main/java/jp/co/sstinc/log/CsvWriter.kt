package jp.co.sstinc.log

import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class CsvWriter(private val path: String) {

    private var writer: BufferedWriter? = null

    fun open() {
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(path), Charsets.UTF_8))
    }

    fun appendHeader() {
        appendLine(listOf("timestamp", "data", "dataSize"))
    }

    fun appendRow(timestamp: String, data: String, dataSize: Int) {
        appendLine(listOf(timestamp, data, dataSize.toString()))
    }

    fun close() {
        writer?.close()
        writer = null
    }

    private fun appendLine(values: List<String>) {
        val line = values.joinToString(",") { escapeCsv(it) }
        writer?.apply {
            append(line)
            newLine()
            flush()
        }
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
