package jp.co.sstinc.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class LogWriterTest {
    @Test
    fun write_writesFormattedTextLine() {
        // テスト用の一時txtファイルを作成
        val file = File.createTempFile("log-writer", ".txt")
        try {
            val writer = LogWriter(file.absolutePath)
            val date = Date(0L)
            val expectedTimestamp = with(writer) { date.format().toString() }

            writer.open()
            writer.write(date, "start TS-E1")
            writer.stop()

            // 出力フォーマットを確認
            val text = file.readText()
            assertEquals("${expectedTimestamp},start TS-E1\n", text)
        } finally {
            // ファイルを削除
            file.delete()
        }
    }

    @Test
    fun stop_closesWriterAndClearsReference() {
        // テスト用の一時txtファイルを作成
        val file = File.createTempFile("log-writer", ".txt")
        try {
            val writer = LogWriter(file.absolutePath)

            // open()後に内部参照が保持されることを確認
            writer.open()
            assertTrue(writer.writer != null)

            // stop後は内部参照がクリアされ、ファイルに追記されていないことを検証
            writer.stop()
            assertNull(writer.writer)
            assertEquals("", file.readText())
        } finally {
            // ファイルを削除
            file.delete()
        }
    }
}