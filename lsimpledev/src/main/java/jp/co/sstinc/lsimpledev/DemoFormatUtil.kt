package jp.co.sstinc.lsimpledev

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/*
 * デモ用のフォーマット(fid = 0b11, sid = 0x00)を解析するためのユーティリティ
 * [データタイプ1(1byte)][データ1][データタイプ2(1byte)][データ2]...
 * データタイプはデータの名前とデータの型を示す
 */

fun ByteArray.toDemoDataString(): String {
    return try {
        this.getDemoData().joinToString(" / ") { "${it.name}: $it" }
    } catch (e: BufferUnderflowException) {
        "不正なデータ"
    }
}

@Throws(BufferUnderflowException::class)
fun ByteArray.getDemoData(): List<DemoData> {
    val buffer = ByteBuffer.wrap(this)
    val result = mutableListOf<DemoData>()

    while (buffer.hasRemaining()) {
        when (val type: Int = buffer.get().toUByte().toInt()) {
            0x01 -> result.add(DemoData.Temperature(buffer.float))
            0x02 -> result.add(DemoData.Humidity(buffer.float))
            0x00, 0x50, 0x53, 0x54, 0xA0, 0xEB -> {
                result.add(DemoData.Bytes(buffer.getRemainingBytes()))
            }
            else -> {
                result.add(DemoData.Unknown(type, buffer.getRemainingBytes()))
            }
        }
    }

    return result.toList()
}

fun ByteBuffer.getRemainingBytes(): ByteArray {
    val result = ByteArray(this.remaining())
    this.get(result)
    return result
}

sealed class DemoData {
    abstract val name: String
    data class Temperature(val temperature: Float) : DemoData() {
        override val name: String get() = "温度"
        override fun toString() = "%.3f°C".format(temperature)
    }
    data class Humidity(val humidity: Float) : DemoData() {
        override val name: String get() = "湿度"
        override fun toString() = "%.3f%%".format(humidity)
    }
    data class Bytes(val data: ByteArray) : DemoData() {
        override val name: String get() = "任意データ"
        override fun toString() = data.joinToString("") { "%02x".format(it) }
    }
    data class Unknown(val type: Int, val data: ByteArray) : DemoData() {
        override val name: String get() = "不明なデータ(0x%02X)".format(type)
        override fun toString() = data.joinToString("") { "%02x".format(it) }
    }
}
