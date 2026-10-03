package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.familymode.FamilyModeSource
import me.rerere.rikkahub.data.familymode.FamilyModeStore
import me.rerere.rikkahub.data.familymode.FamilySettingsSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.instance.InstanceFactory

/**
 * 结构化 DI 回归：Koin `single { Impl(...) }` 只索引主类型，构造参数为接口时
 * 必须显式绑定，否则 `get<FamilyModeController>()` 会在启动时抛
 * `NoDefinitionFoundException`（真机首启崩溃根因）。
 *
 * 该测试不解析实例（无需 Android Context），只校验四个定义的主类型已注册；
 * Koin 运行时能否真正完成解析仍以真机 App 启动为准。
 */
class FamilyModeModuleDiStructureTest {

    @Test
    fun `family mode module registers controller dependency interface types`() {
        @Suppress("UNCHECKED_CAST")
        val mappings = familyModeModule.javaClass
            .getMethod("getMappings")
            .invoke(familyModeModule) as Map<*, *>

        val primaryTypes = mappings.values
            .mapNotNull { (it as? InstanceFactory<*>)?.beanDefinition?.primaryType?.java?.name }
            .toSet()

        assertTrue(
            "FamilyModeStore missing: $primaryTypes",
            FamilyModeStore::class.java.name in primaryTypes,
        )
        assertTrue(
            "FamilyModeController missing: $primaryTypes",
            FamilyModeController::class.java.name in primaryTypes,
        )
        assertTrue(
            "FamilyModeSource missing (controller store param): $primaryTypes",
            FamilyModeSource::class.java.name in primaryTypes,
        )
        assertTrue(
            "FamilySettingsSource missing (controller settingsSource param): $primaryTypes",
            FamilySettingsSource::class.java.name in primaryTypes,
        )
    }
}
