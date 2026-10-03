package me.rerere.rikkahub.di

import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.familymode.FamilyModeSource
import me.rerere.rikkahub.data.familymode.FamilyModeStore
import me.rerere.rikkahub.data.familymode.FamilySettingsSource
import org.koin.dsl.module

/**
 * 家人模式核心依赖。
 *
 * 控制器不注入 WebServerManager，避免 ChatService -> FamilyModeController -> WebServerManager
 * -> ChatService 的 DI 环。Web 停服回调由 RikkaHubApp 在 DI 就绪后通过
 * [FamilyModeController.setWebShutdownHandler] 绑定。
 *
 * 接口绑定（[FamilyModeSource] / [FamilySettingsSource]）必须显式注册：Koin 对
 * `single { Impl(...) }` 只索引主类型，构造参数类型为接口时会解析失败。
 * `get<具体实现>()` 复用既有单例，保持实例同一性。
 */
val familyModeModule = module {
    single<FamilyModeStore> {
        FamilyModeStore(
            context = get(),
            scope = get<AppScope>(),
        )
    }

    // FamilyModeController(store: FamilyModeSource) -> 与 FamilyModeStore 同一实例。
    single<FamilyModeSource> { get<FamilyModeStore>() }

    // FamilyModeController(settingsSource: FamilySettingsSource) -> 与 SettingsStore 同一实例。
    single<FamilySettingsSource> { get<SettingsStore>() }

    single<FamilyModeController> {
        FamilyModeController(
            store = get(),
            settingsSource = get(),
            // 构造参数类型是 CoroutineScope；appModule 只注册 AppScope，必须显式指定。
            scope = get<AppScope>(),
        )
    }
}
