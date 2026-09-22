package ru.yourok.torrserve.ui.fragments.main.torrents

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.atv.Utils
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.activities.main.MainActivity
import ru.yourok.torrserve.ui.activities.play.PlayActivity
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.utils.TorrentHelper


class TorrentsFragment : TSFragment() {

    private var torrentAdapter: TorrentsAdapter? = null
    private lateinit var emptyView: TextView
    private var sortMode: Int = Settings.get("sort_mode_int", 0)    //Изменение типа данных с логического на целочисленный для обеспечения возможности хранения и циклического переключения шести различных режимов сортировки (от 0 до 5) вместо двух.
    private var currentCategory: String = ""
    private var currentSearchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vi = inflater.inflate(R.layout.main_fragment, container, false)
        torrentAdapter = TorrentsAdapter(requireActivity())
        emptyView = vi.findViewById(R.id.empty_view)
        vi.findViewById<ListView>(R.id.lvTorrents)?.let { lvTorrents ->
            lvTorrents.adapter = torrentAdapter
            lvTorrents.setOnItemClickListener { _, _, i, _ ->
                val torr = torrentAdapter?.getItem(i) as Torrent
                val intent = Intent(App.context, PlayActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                intent.action = Intent.ACTION_VIEW
                intent.putExtra("hash", torr.hash)
                intent.putExtra("title", torr.title)
                torr.category?.let { if (it.isNotBlank()) intent.putExtra("category", it) }
                intent.putExtra("poster", torr.poster)
                intent.putExtra("action", "play")
                App.context.startActivity(intent)
            }
            lvTorrents.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
            lvTorrents.setMultiChoiceModeListener(TorrentsActionBar(lvTorrents))
        }
        return vi
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            start()

            val category =
                arguments?.getString("category") ?: ""

            if (category.isNotEmpty()) {
                filter(category)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isViewModelInitialized())
            (viewModel as TorrentsViewModel).setUpdate(false)
    }

    private fun applySort(list: List<Torrent>): List<Torrent> {
        return when (sortMode) {
            0 -> list.sortedBy { it.timestamp }
            1 -> list.sortedByDescending { it.timestamp }
            2 -> list.sortedBy { it.title }
            3 -> list.sortedByDescending { it.title }
            4 -> list.sortedBy { it.torrent_size }
            5 -> list.sortedByDescending { it.torrent_size }
            else -> list
        }
    }

    fun sort() {
        sortMode = (sortMode + 1) % 6
        Settings.set("sort_mode_int", sortMode)
        
        val list = torrentAdapter?.list ?: return
        val sortedList = applySort(list)
        torrentAdapter?.update(sortedList)
        
        when (sortMode) {
            0 -> App.toast("Сортировка: по дате (возрастание)")
            1 -> App.toast("Сортировка: по дате (убывание)")
            2 -> App.toast("Сортировка: по имени (возрастание)")
            3 -> App.toast("Сортировка: по имени (убывание)")
            4 -> App.toast("Сортировка: по размеру (возрастание)")
            5 -> App.toast("Сортировка: по размеру (убывание)")
        }
        
        activity?.findViewById<ListView>(R.id.lvTorrents)?.setSelection(0)
    }

    private fun applyFiltersAndSort(list: List<Torrent>): List<Torrent> {
        var filteredList = list
        if (currentCategory == "uncategorized") {
            filteredList = filteredList.filter { it.category.isNullOrBlank() }
        } else if (currentCategory.isNotBlank()) {
            filteredList = filteredList.filter { it.category?.contains(currentCategory, true) == true }
        }
        if (currentSearchQuery.isNotBlank()) {
            filteredList = filteredList.filter { it.title?.contains(currentSearchQuery, true) == true }
        }
        return applySort(filteredList)
    }

    private fun dispatchUpdatedList(rawList: List<Torrent>) {
        val processedList = applyFiltersAndSort(rawList)
        torrentAdapter?.update(processedList)
        emptyView.visibility = if (processedList.isEmpty()) View.VISIBLE else View.GONE
    }

    fun setFilter(query: String) {
        currentSearchQuery = query
        val data = (viewModel as? TorrentsViewModel)?.getData()
        val rawList = data?.value ?: emptyList()
        dispatchUpdatedList(rawList)
    }

    suspend fun filter(cat: String = "") = withContext(Dispatchers.Main) {
        currentCategory = cat
        val data = (viewModel as TorrentsViewModel).getData()
        val rawList = data.value ?: emptyList()
        dispatchUpdatedList(rawList)
    }

    suspend fun start() = withContext(Dispatchers.Main) {
        viewModel = ViewModelProvider(this@TorrentsFragment)[TorrentsViewModel::class.java]
        val data = (viewModel as TorrentsViewModel).getData()
        (viewModel as TorrentsViewModel).setUpdate(true)
        data.observe(viewLifecycleOwner) { rawList ->
            dispatchUpdatedList(rawList)
            (activity as? MainActivity)?.setupSortFab()
        }
    }

    fun onKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_X -> {
                return true
            }
        }
        return false
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_INFO -> {
                activity?.currentFocus?.let {
                    it.findViewById<ListView>(R.id.lvTorrents)?.let { lv ->
                        val itemPosition = lv.selectedItemPosition
                        if (itemPosition in torrentAdapter!!.list.indices) {
                            torrentAdapter!!.list[itemPosition].let {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val torrent = TorrentHelper.waitFiles(it.hash) ?: let {
                                        return@launch
                                    }
                                    TorrentHelper.showFFPInfo(lv.context, "", torrent)
                                }
                            }
                        }
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_X -> {
                sort()
                if (Utils.isTvBox()) return true
            }

        }
        return false
    }
}