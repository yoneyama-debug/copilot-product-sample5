package jp.co.sstinc.lsimpledev

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import jp.co.sstinc.lsimpledev.databinding.FragmentServerSettingsBinding
import jp.co.sstinc.net.EventPoster
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerSettingsFragment : Fragment() {

    private var _binding: FragmentServerSettingsBinding? = null
    private val binding get() = _binding!!

    private val eventPoster = EventPoster()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.postSwitch.isChecked = prefs.getBoolean(PREFS_KEY_POST_ENABLED, false)
        binding.postUrlEdit.setText(prefs.getString(PREFS_KEY_POST_URL, "") ?: "")
        binding.resultText.text = getString(R.string.settings_result_idle)

        updatePostControls(binding.postSwitch.isChecked)

        binding.postSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(PREFS_KEY_POST_ENABLED, isChecked) }
            updatePostControls(isChecked)
        }

        binding.postUrlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) =
                Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable) {
                prefs.edit { putString(PREFS_KEY_POST_URL, s.toString() ) }
                //　URLが有効かチェックして、不正な場合はエラー表現にする(時間があれば実装するので良い)
            }
        })



        binding.testButton.setOnClickListener {
            testPost()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun testPost() {
        val url = binding.postUrlEdit.text?.toString()
        val httpUrl = url?.toHttpUrlOrNull()
        if (httpUrl == null) {
            binding.resultText.text = getString(R.string.invalid_url)
            return
        }

        binding.resultText.text = getString(R.string.testing)
        binding.testButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = eventPoster.postTestEvent(httpUrl)
            binding.resultText.text = if (result.isSuccess) {
                getString(R.string.test_post_success)
            } else {
                getString(
                    R.string.test_post_failed,
                    result.exceptionOrNull()?.message ?: getString(R.string.post_error_text)
                )
            }
            binding.testButton.isEnabled = binding.postSwitch.isChecked
        }
    }

    private fun updatePostControls(postEnabled: Boolean) {
        binding.postUrlLayout.isEnabled = postEnabled
        binding.postUrlEdit.isEnabled = postEnabled
        binding.testButton.isEnabled = postEnabled
    }
}
