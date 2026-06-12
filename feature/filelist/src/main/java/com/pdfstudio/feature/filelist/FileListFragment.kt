package com.pdfstudio.feature.filelist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pdfstudio.feature.filelist.databinding.FragmentFileListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FileListFragment : Fragment() {

    interface Callback {
        fun onOpenPdfPicker()
        fun onOpenRecent(uri: String)
    }

    private var _binding: FragmentFileListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileListViewModel by viewModels()
    private val adapter = RecentFileAdapter { item ->
        (activity as? Callback)?.onOpenRecent(item.uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFileListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerRecent.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecent.adapter = adapter
        binding.btnOpenPdf.setOnClickListener {
            (activity as? Callback)?.onOpenPdfPicker()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentFiles.collect { files ->
                    adapter.submitList(files)
                    binding.tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = FileListFragment()
    }
}
