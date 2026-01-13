package org.effekthasch.quickshopper3k.ui.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.DialogAddItemBinding
import org.effekthasch.quickshopper3k.databinding.FragmentStoreEditBinding

class StoreEditFragment : Fragment() {
    private var _binding: FragmentStoreEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: StoreEditViewModel
    private lateinit var adapter: StoreEditAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoreEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val storeId = arguments?.getLong("storeId") ?: return

        val database = AppDatabase.getDatabase(requireContext())
        val repository = ShoppingRepository(database)
        viewModel = ViewModelProvider(
            this,
            StoreEditViewModelFactory(repository)
        )[StoreEditViewModel::class.java]

        viewModel.setStoreId(storeId)

        setupRecyclerView()
        setupMenu()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = StoreEditAdapter(
            onAvailabilityChanged = { storeItem, isAvailable ->
                viewModel.setItemAvailability(storeItem.item.id, isAvailable) { result ->
                    result.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            }
        )

        binding.itemsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.itemsRecyclerView.adapter = adapter

        val callback = DragCallback()
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.itemsRecyclerView)
    }

    private fun setupMenu() {
        binding.menuFab.setOnClickListener { view ->
            val storeId = arguments?.getLong("storeId") ?: 0L
            PopupMenu(requireContext(), view).apply {
                if (storeId != 0L) {
                    menu.add(0, 1, 0, R.string.edit_store_title)
                    menu.add(0, 2, 0, R.string.delete_store_title)
                }
                menu.add(0, 3, 0, "Back")

                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            showEditStoreDialog()
                            true
                        }

                        2 -> {
                            showDeleteStoreDialog()
                            true
                        }

                        3 -> {
                            findNavController().navigateUp()
                            true
                        }

                        else -> false
                    }
                }
                show()
            }
        }
    }

    private fun observeData() {
        viewModel.items.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
        }

        viewModel.store.observe(viewLifecycleOwner) { store ->
            val storeId = arguments?.getLong("storeId") ?: 0L
            if (storeId == 0L) {
                activity?.title = getString(R.string.default_order_title)
            } else {
                activity?.title = store?.name ?: getString(R.string.store_edit_mode_title)
            }
        }
    }

    private fun showEditStoreDialog() {
        val dialogBinding = DialogAddItemBinding.inflate(layoutInflater)
        dialogBinding.itemNameInputLayout.hint = getString(R.string.store_name)
        dialogBinding.itemNameInput.setText(viewModel.store.value?.name ?: "")

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_store_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = dialogBinding.itemNameInput.text?.toString() ?: ""
                if (newName.isNotBlank()) {
                    viewModel.updateStoreName(newName) { result ->
                        result.onSuccess {
                            Snackbar.make(binding.root, R.string.store_updated, Snackbar.LENGTH_SHORT).show()
                        }.onFailure {
                            Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Snackbar.make(binding.root, R.string.error_empty_name, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialogBinding.itemNameInput.requestFocus()
    }

    private fun showDeleteStoreDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_store_title)
            .setMessage(R.string.delete_store_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteStore { result ->
                    result.onSuccess {
                        Snackbar.make(binding.root, R.string.store_deleted, Snackbar.LENGTH_SHORT).show()
                        findNavController().navigateUp()
                    }.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class DragCallback : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPosition = viewHolder.bindingAdapterPosition
            val toPosition = target.bindingAdapterPosition

            adapter.moveItem(fromPosition, toPosition)
            viewModel.updateItemOrder(fromPosition, toPosition, adapter.currentList)

            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        }

        override fun isLongPressDragEnabled(): Boolean = false

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewModel.savePendingOrderUpdates { result ->
                result.onFailure {
                    Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
