package jp.co.sstinc.lsimpledev

import android.graphics.Typeface
import android.widget.TextView
import androidx.databinding.BindingAdapter

@BindingAdapter("textStyleInt")
fun setTextStyleInt(textView: TextView, style: Int) {
    val baseTypeface = textView.typeface ?: Typeface.DEFAULT
    textView.typeface = Typeface.create(baseTypeface, style)
}