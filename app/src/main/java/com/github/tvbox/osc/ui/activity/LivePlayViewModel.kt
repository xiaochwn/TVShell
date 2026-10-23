package com.github.tvbox.osc.ui.activity

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LanguageManager
import com.google.gson.JsonArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class PageState { LOADING, EMPTY, READY }

internal data class ChannelInfoUi(
    val name: String = "",
    val num: Int = 0,
    val sourceText: String = "",
    val currentEpgTime: String = "",
    val currentEpgTitle: String = "",
    val nextEpgTime: String = "",
    val nextEpgTitle: String = "",
)

internal data class LiveEpgUi(
    val sheetVisible: Boolean = false,
    val channelName: String = "",
    val epgList: List<Epginfo> = emptyList(),
    val lookBackIndex: Int = -1,
    val canCatchup: Boolean = false,
)

internal data class LiveTimeshiftUi(
    val position: Int = 0,
    val duration: Int = 0,
    val isShiyi: Boolean = false,
    val isBackState: Boolean = false,
)

internal data class LiveChannelListUi(
    val rows: List<LiveListRow> = emptyList(),
    val tappedGroupIndex: Int = 0,
    val playingGroupIndex: Int = 0,
    val playingChannelIndex: Int = -1,
    val playingChannel: LiveChannelItem? = null,
    val expandedGroups: Set<Int> = emptySet(),
    val lockedGroups: Set<Int> = emptySet(),
    val confirmedPasswordGroups: Set<Int> = emptySet(),
    val scrollRequestId: Long = 0,
)

internal data class LivePageFrame(
    val pageState: PageState = PageState.LOADING,
    val fullScreen: Boolean = false,
    val rotating: Boolean = false,
    val landscapeNow: Boolean = false,
) {
    val isFullBox: Boolean get() = if (rotating) landscapeNow else fullScreen
}

internal data class LivePlayerUi(
    val playState: PlayState = PlayState.IDLE,
    val snapshotVisible: Boolean = false,
    val snapshotBitmap: Bitmap? = null,
    val resolutionText: String = "",
    val resolutionVisible: Boolean = false,
    val gestureHintText: String? = null,
)

internal data class LiveOverlayUi(
    val visible: Boolean = false,
    val showTimeOn: Boolean = false,
    val showNetSpeedOn: Boolean = false,
    val timeText: String = "",
    val netSpeedText: String = "",
)

internal data class LivePlayUiState(
    val frame: LivePageFrame = LivePageFrame(),
    val player: LivePlayerUi = LivePlayerUi(),
    val overlay: LiveOverlayUi = LiveOverlayUi(),
    val channelInfo: ChannelInfoUi = ChannelInfoUi(),
    val passwordDialogTarget: Pair<Int, Int>? = null,
    val epg: LiveEpgUi = LiveEpgUi(),
    val timeshift: LiveTimeshiftUi = LiveTimeshiftUi(),
    val channelList: LiveChannelListUi = LiveChannelListUi(),
    val settings: LiveSettingsUi = LiveSettingsUi(),
)

internal class LivePlayViewModel : ViewModel() {

    private val _state = MutableStateFlow(LivePlayUiState())
    val state: StateFlow<LivePlayUiState> = _state.asStateFlow()

    internal fun updateEpg(transform: (LiveEpgUi) -> LiveEpgUi) {
        _state.update { it.copy(epg = transform(it.epg)) }
    }

    internal fun updateTimeshift(transform: (LiveTimeshiftUi) -> LiveTimeshiftUi) {
        _state.update { it.copy(timeshift = transform(it.timeshift)) }
    }

    internal fun updateFrame(transform: (LivePageFrame) -> LivePageFrame) {
        _state.update { it.copy(frame = transform(it.frame)) }
    }

    internal fun updatePlayer(transform: (LivePlayerUi) -> LivePlayerUi) {
        _state.update { it.copy(player = transform(it.player)) }
    }

    internal fun updateOverlay(transform: (LiveOverlayUi) -> LiveOverlayUi) {
        _state.update { it.copy(overlay = transform(it.overlay)) }
    }

    internal fun updateChannelInfo(info: ChannelInfoUi) {
        _state.update { it.copy(channelInfo = info) }
    }

    internal fun updatePasswordDialogTarget(target: Pair<Int, Int>?) {
        _state.update { it.copy(passwordDialogTarget = target) }
    }

    private var channelGroups: List<LiveChannelGroup> = emptyList()

    internal fun updateChannelList(transform: (LiveChannelListUi) -> LiveChannelListUi) {
        _state.update { current ->
            val list = transform(current.channelList)
            val locked = LiveChannelRows.lockedGroups(channelGroups, list.confirmedPasswordGroups)
            val structureChanged = list.expandedGroups != current.channelList.expandedGroups ||
                locked != current.channelList.lockedGroups
            current.copy(
                channelList = if (structureChanged) {
                    list.copy(lockedGroups = locked, rows = LiveChannelRows.of(channelGroups, list.expandedGroups, locked))
                } else {
                    list.copy(lockedGroups = locked)
                },
            )
        }
    }

    internal fun onChannelGroupsChanged(groups: List<LiveChannelGroup>) {
        channelGroups = groups
        _state.update { current ->
            val locked = LiveChannelRows.lockedGroups(channelGroups, current.channelList.confirmedPasswordGroups)
            current.copy(
                channelList = current.channelList.copy(
                    lockedGroups = locked,
                    rows = LiveChannelRows.of(channelGroups, current.channelList.expandedGroups, locked),
                ),
            )
        }
    }

    internal fun updateSettings(transform: (LiveSettingsUi) -> LiveSettingsUi) {
        _state.update { it.copy(settings = transform(it.settings)) }
    }

    private var settingsSource: LiveSettingsSource? = null

    internal fun attachSettingsSource(source: LiveSettingsSource) {
        settingsSource = source
    }

    internal fun onSettingsOpened() {
        refreshSettingsSnapshot()
        _state.update { it.copy(settings = it.settings.copy(sheetVisible = true)) }
    }

    internal fun onSettingsInputsChanged() {
        if (!_state.value.settings.sheetVisible) return
        refreshSettingsSnapshot()
    }

    internal fun onSettingRemoved(itemIndex: Int, host: Host) {
        host.removeConfigHistory(itemIndex)
        refreshSettingsSnapshot()
    }

    private fun refreshSettingsSnapshot() {
        val source = settingsSource ?: return
        val groups = LiveSettingsSnapshot.of(source, _state.value.channelList.playingChannel)
        _state.update { it.copy(settings = it.settings.copy(groups = groups)) }
    }

    private fun str(resId: Int, vararg args: Any): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    internal interface Host {
        fun currentChannelItem(): LiveChannelItem?

        fun currentPlayerScale(): Int

        fun currentPlayerType(): Int

        fun replayCurrentChannel()

        fun applyPlayerScale(position: Int)

        fun applyPlayerType(position: Int)

        fun releasePlayerKernel()

        fun refreshTimeOverlay()

        fun refreshNetSpeedOverlay()

        fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int)

        fun setEmptyChannelList(releasePlayer: Boolean)

        fun removeConfigHistory(itemIndex: Int)

        fun toast(msg: String)

        fun isFinishing(): Boolean

        fun postToMain(action: Runnable)
    }

    private var liveConfigRequestId = 0

    fun onSettingClicked(groupIndex: Int, position: Int, host: Host) {
        if (groupIndex in 0..2 && host.currentChannelItem() == null) {
            host.toast(str(R.string.live_please_select_channel))
            return
        }
        when (groupIndex) {
            0 -> {
                val item = host.currentChannelItem() ?: return
                if (position < 0 || position >= item.sourceNum || position == item.sourceIndex) return
                item.sourceIndex = position
                host.replayCurrentChannel()
            }
            1 -> {
                if (position == host.currentPlayerScale()) return
                host.applyPlayerScale(position)
            }
            2 -> {
                if (position == host.currentPlayerType()) return
                host.applyPlayerType(position)
            }
            3 -> {
                if (position == KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)) return
                KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position)
            }
            4 -> {
                when (position) {
                    0 -> KV.put(HawkConfig.LIVE_SHOW_TIME, !KV.get(HawkConfig.LIVE_SHOW_TIME, false)).also { host.refreshTimeOverlay() }
                    1 -> KV.put(HawkConfig.LIVE_SHOW_NET_SPEED, !KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)).also { host.refreshNetSpeedOverlay() }
                    2 -> KV.put(HawkConfig.LIVE_CHANNEL_REVERSE, !KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                    3 -> KV.put(HawkConfig.LIVE_CROSS_GROUP, !KV.get(HawkConfig.LIVE_CROSS_GROUP, false))
                }
            }
            5 -> {
                if (position == ApiConfig.getLiveGroupIndex()) return
                val currentChannelName = preferredRefreshChannelName(host)
                val currentSourceIndex = preferredRefreshSourceIndex(host)
                val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
                if (liveGroups == null || position >= liveGroups.size()) return
                liveConfigRequestId++
                val livesOBJ = liveGroups.get(position).asJsonObject
                ApiConfig.setLiveGroupIndex(position)
                ApiConfig.get().loadLiveApi(livesOBJ)
                if (ApiConfig.get().channelGroupList.isEmpty()) {
                    host.releasePlayerKernel()
                    host.setEmptyChannelList(false)
                    return
                }
                host.refreshChannelListAndPlay(currentChannelName, currentSourceIndex)
            }
            6 -> {
                val target: String
                if (position == 0) {
                    if (ApiConfig.isLiveFollowVod()) return
                    target = ""
                } else {
                    target = ApiConfig.get().getLiveApiHistoryUrl(position)
                    if (target.isEmpty() || target == KV.get(HawkConfig.LIVE_API_URL, "")) return
                }
                val configChannelName = preferredRefreshChannelName(host)
                val configSourceIndex = preferredRefreshSourceIndex(host)
                val requestId = ++liveConfigRequestId
                if (target.isNotEmpty() && BootGuard.isDisabledSource(target)) {
                    host.toast(str(R.string.live_source_auto_disabled))
                    return
                }
                KV.put(HawkConfig.LIVE_API_URL, target)
                if (target.isEmpty()) {
                    HistoryHelper.clearLiveApiLineList()
                } else {
                    HistoryHelper.setLiveApiHistory(target)
                    if (!HistoryHelper.isLiveApiLineUrl(target)) HistoryHelper.clearLiveApiLineList()
                }
                ApiConfig.get().clearLiveHosts()
                ApiConfig.get().invalidateLiveConfig()
                ApiConfig.get().refreshLiveApiHistoryItems()
                ApiConfig.get().loadLiveConfig(false, object : ApiConfig.LoadConfigCallback {
                    override fun success() {
                        host.postToMain(Runnable {
                            if (requestId != liveConfigRequestId || host.isFinishing()) return@Runnable
                            host.refreshChannelListAndPlay(configChannelName, configSourceIndex)
                        })
                    }

                    override fun error(msg: String?) {
                        host.postToMain(Runnable {
                            if (requestId != liveConfigRequestId || host.isFinishing()) return@Runnable
                            host.releasePlayerKernel()
                            ApiConfig.get().refreshLiveApiHistoryItems()
                            host.setEmptyChannelList(false)
                            host.toast(msg ?: "")
                        })
                    }

                    override fun notice(msg: String?) {
                        host.postToMain(Runnable {
                            if (requestId != liveConfigRequestId || host.isFinishing()) return@Runnable
                            host.toast(msg ?: "")
                        })
                    }
                })
            }
        }
        refreshSettingsSnapshot()
    }

    private fun preferredRefreshChannelName(host: Host): String? {
        host.currentChannelItem()?.let { return it.channelName }
        return KV.get(HawkConfig.LIVE_CHANNEL, "")
    }

    private fun preferredRefreshSourceIndex(host: Host): Int {
        host.currentChannelItem()?.let { return it.sourceIndex }
        return -1
    }
}
