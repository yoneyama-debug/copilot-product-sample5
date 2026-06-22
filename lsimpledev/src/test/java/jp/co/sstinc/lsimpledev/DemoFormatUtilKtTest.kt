package jp.co.sstinc.lsimpledev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.BufferUnderflowException

class DemoFormatUtilKtTest {
    data class TestData(
        val payload: String,
        val assertName: String,
        val assertData: String
    )

    // 固定長フォーマットのみを対象にしたテストケース
    private val testDataListOnlyFixedSize = listOf(
        TestData("0141c80000", "温度", "25.000°C"),
        TestData("0242480000", "湿度", "50.000%")
    )

    // 固定長 + 可変長 + 未知データ種別を含めたテストケース
    private val testDataList = this.testDataListOnlyFixedSize + listOf(
        TestData("000102030405060708090a", "任意データ", "0102030405060708090a"),
        TestData("500102030405060708090a", "任意データ", "0102030405060708090a"),
        TestData("530102030405060708090a", "任意データ", "0102030405060708090a"),
        TestData("540102030405060708090a", "任意データ", "0102030405060708090a"),
        TestData("a00102030405060708090a", "任意データ", "0102030405060708090a"),
        TestData("eb0102030405060708090a", "任意データ", "0102030405060708090a"),
        TestData("ff0102030405060708090a", "不明なデータ(0xFF)", "0102030405060708090a")
    )

    @Test
    fun getDemoDataTest() {
        // 単一ペイロードが正しく1件としてデコードされることを検証
        testDataList.forEach {
            val testData = it.payload
            println("Start test of $testData")

            val result = testData.asByteArray().getDemoData()
            assertEquals(1, result.size)
            assertEquals(it.assertName, result[0].name)
            assertEquals(it.assertData, result[0].toString())
            println("Pass")
        }

        // 連結した2つのペイロードが順序どおり2件に分割されることを検証
        for (data1 in testDataListOnlyFixedSize) {
            for (data2 in testDataList) {
                val testData = data1.payload + data2.payload
                println("Start test of $testData")

                val result = testData.asByteArray().getDemoData()
                assertEquals(2, result.size)
                assertEquals(data1.assertName, result[0].name)
                assertEquals(data1.assertData, result[0].toString())
                assertEquals(data2.assertName, result[1].name)
                assertEquals(data2.assertData, result[1].toString())
                println("Pass")
            }
        }

        // 不正長ペイロードでは BufferUnderflowException が発生することを検証
        val invalidPayload = "0141c8"
        println("Start test of $invalidPayload")

        assertThrows(BufferUnderflowException::class.java) {
            invalidPayload.asByteArray().getDemoData()
        }
        println("Pass")
    }

    @Test
    fun toDemoDataStringTest() {
        assertEquals("温度: 25.000°C", "0141c80000".asByteArray().toDemoDataString())
        assertEquals("不正なデータ", "0141c8".asByteArray().toDemoDataString())
    }

    private fun String.asByteArray(): ByteArray {
        // 奇数桁の16進文字列は先頭をゼロ埋めして2桁単位でバイト化
        val hexString = if (length % 2 == 0) this else "0${this}"
        return hexString.chunked(2).map { it.asByte() }.toByteArray()
    }

    private fun String.asByte() = substring(0, 2).toUByte(16).toByte()
}