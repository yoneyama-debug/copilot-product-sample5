package jp.co.sstinc.file

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException


@Throws(IOException::class)
fun copyFileToDownloads(context: Context, srcPath: String, dstName: String, dstMimeType: String) {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        FileInputStream(srcPath).use { `in` ->
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, dstName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, dstMimeType)
            values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
            )
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri: Uri = context.getContentResolver()
                .insert(MediaStore.Files.getContentUri("external"), values)!!
            context.getContentResolver().openOutputStream(uri).use { out ->
                if (out == null) {
                    return
                }
                val buf = ByteArray(1024)
                var len: Int
                while (`in`.read(buf).also { len = it } > 0) {
                    out.write(buf, 0, len)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.getContentResolver().update(uri, values, null, null)
        }
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, dstName)

        FileInputStream(srcPath).use { `in` ->
            file.outputStream().use { out ->
                val buf = ByteArray(1024)
                var len: Int
                while (`in`.read(buf).also { len = it } > 0) {
                    out.write(buf, 0, len)
                }
            }
        }

    }
}
