package org.heyogesh.drive.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.heyogesh.drive.R
import org.heyogesh.drive.api.DriveItem
import org.heyogesh.drive.data.NetworkMonitor
import org.heyogesh.drive.databinding.ActivityMainBinding
import org.heyogesh.drive.downloads.DownloadRepository

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: DriveViewModel
    private lateinit var adapter: DriveAdapter
    private lateinit var downloads: DownloadRepository
    private lateinit var network: NetworkMonitor
    private var allItems: List<DriveItem> = emptyList()
    private var query = ""
    private var sort = Sort.NAME
    private var grid = false
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[DriveViewModel::class.java]
        downloads = DownloadRepository(applicationContext)
        network = NetworkMonitor(applicationContext)
        adapter = DriveAdapter(::openItem, ::selectionChanged, ::fiveSecondDownload)
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.toolbar.setOnMenuItemClickListener(::onMenu)
        setupSearch()

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        viewModel.load()
    }

    private fun setupSearch() {
        val search = binding.toolbar.menu.findItem(R.id.action_search).actionView as SearchView
        search.queryHint = "Search this folder"
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(value: String?) = false
            override fun onQueryTextChange(value: String?): Boolean {
                query = value.orEmpty()
                submitVisibleItems()
                return true
            }
        })
    }

    private fun render(state: BrowserState) {
        allItems = state.items
        binding.swipeRefresh.isRefreshing = state.isLoading && allItems.isNotEmpty()
        binding.loading.visibility = if (state.isLoading && allItems.isEmpty()) View.VISIBLE else View.GONE
        renderBreadcrumbs(state.path)
        submitVisibleItems()
        when {
            state.error != null -> showState("Couldn’t open this folder", if (!network.isOnline()) "You’re offline. Reconnect and try again." else state.error)
            !state.isLoading && allItems.isEmpty() -> showState("This folder is empty", "Add files to Windows Documents, then pull down to refresh.", retry = false)
            else -> binding.statePanel.visibility = View.GONE
        }
    }

    private fun showState(title: String, message: String, retry: Boolean = true) {
        binding.statePanel.visibility = View.VISIBLE
        binding.stateTitle.text = title
        binding.stateMessage.text = message
        binding.retryButton.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun submitVisibleItems() {
        val needle = query.trim()
        val result = allItems.asSequence()
            .filter { needle.isBlank() || it.name.contains(needle, ignoreCase = true) }
            .sortedWith(Comparator { a, b ->
                val foldersFirst = (a.kind == "folder").compareTo(b.kind == "folder")
                if (foldersFirst != 0) -foldersFirst else when (sort) {
                    Sort.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    Sort.SIZE -> (b.size ?: -1).compareTo(a.size ?: -1)
                    Sort.MODIFIED -> b.modifiedAt.compareTo(a.modifiedAt)
                }
            }).toList()
        adapter.submit(result)
    }

    private fun renderBreadcrumbs(path: String) {
        binding.breadcrumbContainer.removeAllViews()
        breadcrumb("Documents", "")
        var built = ""
        path.split('/').filter(String::isNotBlank).forEach { part ->
            val divider = TextView(this).apply { text = "›"; setTextColor(getColor(R.color.slate)); textSize = 18f; setPadding(8, 0, 8, 0) }
            binding.breadcrumbContainer.addView(divider)
            built = if (built.isBlank()) part else "$built/$part"
            breadcrumb(part, built)
        }
        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun breadcrumb(label: String, target: String) {
        binding.breadcrumbContainer.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.cobalt))
            setPadding(4, 0, 4, 0)
            isClickable = true
            setOnClickListener { if (target != viewModel.state.value.path) viewModel.load(target) }
        })
    }

    private fun selectionChanged(count: Int) {
        val menu = binding.toolbar.menu
        val selecting = count > 0
        binding.toolbar.title = if (selecting) "$count selected" else "Heyogesh Drive"
        menu.findItem(R.id.action_download).isVisible = selecting
        menu.findItem(R.id.action_search).isVisible = !selecting
        menu.findItem(R.id.action_sort).isVisible = !selecting
        menu.findItem(R.id.action_view).isVisible = !selecting
    }

    private fun onMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_download -> { queueSelection(adapter.selectedItems()); true }
        R.id.action_downloads -> { startActivity(Intent(this, DownloadActivity::class.java)); true }
        R.id.action_view -> { toggleView(); true }
        R.id.action_sort -> { showSortMenu(); true }
        R.id.action_sign_out -> { signOut(); true }
        else -> false
    }

    private fun toggleView() {
        grid = !grid
        binding.fileList.layoutManager = if (grid) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        adapter.setGridMode(grid)
        binding.toolbar.menu.findItem(R.id.action_view).title = if (grid) "List view" else "Grid view"
    }

    private fun showSortMenu() {
        PopupMenu(this, binding.toolbar).apply {
            menu.add(0, 1, 0, "Name")
            menu.add(0, 2, 1, "Size")
            menu.add(0, 3, 2, "Modified")
            setOnMenuItemClickListener {
                sort = when (it.itemId) { 2 -> Sort.SIZE; 3 -> Sort.MODIFIED; else -> Sort.NAME }
                submitVisibleItems()
                true
            }
        }.show()
    }

    private fun openItem(item: DriveItem) {
        if (item.kind == "folder") {
            TransitionManager.beginDelayedTransition(binding.fileList)
            binding.fileList.animate().alpha(0.35f).setDuration(110).withEndAction {
                binding.fileList.alpha = 1f
                viewModel.load(item.path)
            }.start()
            return
        }
        lifecycleScope.launch {
            try {
                val opened = viewModel.api.open(item.path)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(opened.streamUrl), opened.mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(Intent.createChooser(intent, "Open ${item.name}"))
            } catch (error: Exception) {
                Snackbar.make(binding.root, error.message ?: "Could not open this file.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun fiveSecondDownload(item: DriveItem) {
        adapter.clearSelection()
        queueSelection(listOf(item))
    }

    private fun queueSelection(items: List<DriveItem>) {
        if (items.isEmpty()) return
        askNotificationPermission()
        lifecycleScope.launch {
            try {
                if (items.size == 1 && items.first().kind != "folder") {
                    val item = items.first()
                    downloads.enqueueFile(item, viewModel.api.downloadUrl(item.path))
                    Snackbar.make(binding.root, "Download started", Snackbar.LENGTH_SHORT).show()
                } else {
                    val archive = viewModel.api.createArchive(items.map { it.path })
                    downloads.enqueueArchive(archive.id, if (items.size == 1) "${items[0].name}.zip" else "Heyogesh Drive selection.zip")
                    Snackbar.make(binding.root, "Preparing your download", Snackbar.LENGTH_SHORT).show()
                }
                adapter.clearSelection()
                startActivity(Intent(this@MainActivity, DownloadActivity::class.java))
            } catch (error: Exception) {
                Snackbar.make(binding.root, error.message ?: "Could not queue download.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun signOut() {
        viewModel.api.sessionStore().clear()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Handled for legacy Android back navigation")
    override fun onBackPressed() {
        when {
            adapter.hasSelection() -> adapter.clearSelection()
            viewModel.state.value.path.isNotBlank() -> viewModel.goUp()
            else -> super.onBackPressed()
        }
    }

    private enum class Sort { NAME, SIZE, MODIFIED }
}
