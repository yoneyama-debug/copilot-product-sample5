package jp.co.sstinc.log

import android.text.format.DateFormat
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.*

class LogWriter(val path : String) {


    var writer : BufferedWriter? = null

    fun open() {

        val outputStream: OutputStream

        outputStream = FileOutputStream(path)
        writer = BufferedWriter(OutputStreamWriter(outputStream, "UTF-8"))


    }

    fun stop() {
        writer?.close()
        writer = null
    }

    fun Date.format() : CharSequence {
        return DateFormat.format("yyyy-MM-dd'T'HH:mm:ss.SSS", this)
    }

    fun write(date : Date, data : String) {
        val dateText = date.format()
        writer?.append("${dateText},${data}")
        writer?.newLine()
    }
}
