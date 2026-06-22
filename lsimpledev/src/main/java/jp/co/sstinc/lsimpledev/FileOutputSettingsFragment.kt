package jp.co.sstinc.lsimpledev

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import jp.co.sstinc.lsimpledev.databinding.FragmentFileOutputSettingsBinding
import android.app.DownloadManager
import android.content.Intent
import androidx.core.content.edit

class FileOutputSettingsFragment : Fragment() {

    private var _binding: FragmentFileOutputSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFileOutputSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.fileSaveSwitch.isChecked = prefs.getBoolean(PREFS_KEY_FILE_SAVE, false)
        binding.csvSaveSwitch.isChecked = prefs.getBoolean(PREFS_KEY_CSV_SAVE, false)

        binding.fileSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(PREFS_KEY_FILE_SAVE, isChecked) }

        }

        binding.csvSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(PREFS_KEY_CSV_SAVE, isChecked) }
        }

        binding.openDownloadDirectoryButton.setOnClickListener {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}