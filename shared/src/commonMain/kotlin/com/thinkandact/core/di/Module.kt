package com.thinkandact.core.di

import com.thinkandact.data.AuthRepository
import com.thinkandact.data.HistoryRepository
import com.thinkandact.data.PlanRepository
import com.thinkandact.data.ReviewRepository
import com.thinkandact.data.RoutineRepository
import com.thinkandact.network.createHttpClient
import com.thinkandact.ui.execution.ExecutionViewModel
import com.thinkandact.ui.fullplan.FullPlanViewModel
import com.thinkandact.ui.history.HistoryViewModel
import com.thinkandact.ui.login.LoginViewModel
import com.thinkandact.ui.morning.MorningViewModel
import com.thinkandact.ui.reminders.RemindersViewModel
import com.thinkandact.ui.review.ReviewViewModel
import com.thinkandact.ui.routine.RoutineViewModel
import com.thinkandact.voice.AsrSessionProvider
import com.thinkandact.voice.AudioRecorder
import com.thinkandact.voice.CachingAsrSessionProvider
import com.thinkandact.voice.SupabaseAsrSessionProvider
import com.thinkandact.voice.SupabaseAsrUsageReporter
import com.thinkandact.voice.TencentAsrClient
import com.thinkandact.voice.VoiceInputService
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val sharedModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = true
            encodeDefaults = true
        }
    }
    single { createHttpClient(get()) }
    single { com.thinkandact.data.session.SessionState(get()) }
    single { PlanRepository(get(), get(), get()) }
    single { RoutineRepository(get(), get()) }
    single { ReviewRepository(get(), get()) }
    single { HistoryRepository(get(), get()) }
    single { AuthRepository(get(), get(), get()) }

    // 语音输入（FE-ASR-1）
    single { AudioRecorder() }
    single<AsrSessionProvider> {
        CachingAsrSessionProvider(SupabaseAsrSessionProvider(get(), get()))
    }
    single { TencentAsrClient(get(), get(), get()) }
    single { SupabaseAsrUsageReporter(get(), get()) }
    single { VoiceInputService(get(), get(), get(), get()) }

    viewModel { RoutineViewModel(get(), get()) }
    viewModel { MorningViewModel(get(), get()) }
    viewModel { ExecutionViewModel(get(), get(), get()) }
    viewModel { ReviewViewModel(get(), get()) }
    viewModel { HistoryViewModel(get()) }
    viewModel { FullPlanViewModel(get(), get(), get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { RemindersViewModel(get()) }
}

expect fun platformModule(): Module
