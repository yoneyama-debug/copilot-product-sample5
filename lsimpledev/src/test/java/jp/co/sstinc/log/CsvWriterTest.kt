package jp.co.sstinc.log

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CsvWriterTest {

    @Test
    fun appendRow_writesCsvLine() = runBlocking {
        // テスト用の一時CSVファイルを作成
        val file = File.createTempFile("csv-writer", ".csv")
        try {
            // CSVに書き込む(ヘッダ + 1行)
            val writer = CsvWriter(file.absolutePath)
            writer.open()
            writer.appendHeader()
            writer.appendRow("2024-01-01T00:00:00.000", "a1b2", 2)
            writer.close()

            // 出力内容を確認
            val text = file.readText()
            assertTrue(text.contains("\"timestamp\",\"data\",\"dataSize\""))
            assertTrue(text.contains("\"2024-01-01T00:00:00.000\",\"a1b2\",\"2\""))
        } finally {
            // ファイルを削除
            file.delete()
        }
    }
}
