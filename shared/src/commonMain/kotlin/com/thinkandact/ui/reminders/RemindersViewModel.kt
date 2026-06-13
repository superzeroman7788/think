package com.thinkandact.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.PlanRepository
import com.thinkandact.core.time.plannedStartSec
import com.thinkandact.data.remote.TaskRowDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 块三 · 普通任务「App 内横幅」提醒（前台、客户端定时，不走服务端推送）。
 *
 * 只管 **普通任务**（important=false）；★重要任务由 JPush 系统推送负责（块三 BE，待账号到位）。
 * 到点(planned_start 跨过当前 tick)且仍 planned → 弹一条横幅，用户在前台时看到。
 * 不回补开 App 之前已过点的任务，避免一打开就堆一串旧提醒。
 */
class RemindersViewModel(
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val _active = MutableStateFlow<TaskRowDto?>(null)
    val active: StateFlow<TaskRowDto?> = _active.asStateFlow()

    private val reminded = mutableSetOf<String>()
    private var tasks: List<TaskRowDto> = emptyList()

    init {
        viewModelScope.launch {
            loadTasks()
            var lastTick = Clock.System.now()
            var ticks = 0
            while (true) {
                delay(TICK_MS)
                ticks++
                // 每隔几 tick 重拉一次，确认/重排后的最新计划能反映进来。
                if (ticks % REFRESH_EVERY == 0) loadTasks()
                val now = Clock.System.now()
                val nowSec = now.epochSeconds
                if (_active.value == null) {
                    // C8-01: 多条同时到点 → 取 planned_start 最早的一条;仍只在 tick 窗口内触发(不回补历史)。
                    val due = tasks
                        .asSequence()
                        .filter { t ->
                            !t.important &&
                                !t.isPoint &&
                                t.status == STATUS_PLANNED &&
                                t.id !in reminded &&
                                t.plannedStart.crossed(lastTick, now)
                        }
                        .mapNotNull { t -> t.plannedStartSec()?.let { sec -> t to sec } }
                        .minByOrNull { it.second }
                        ?.first
                    if (due != null) {
                        reminded.add(due.id)
                        _active.value = due
                    }
                }
                lastTick = now
            }
        }
    }

    fun dismiss() {
        _active.value = null
    }

    private suspend fun loadTasks() {
        runCatching { planRepository.fetchTodayTasks() }.onSuccess { tasks = it }
    }

    /** planned_start 落在 (from, to] 区间内 = 在这次 tick 期间到点。 */
    private fun String?.crossed(from: Instant, to: Instant): Boolean {
        val t = this?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return t > from && t <= to
    }

    private companion object {
        const val TICK_MS = 20_000L
        const val REFRESH_EVERY = 9 // 约 3 分钟重拉一次
        const val STATUS_PLANNED = "planned"
    }
}
