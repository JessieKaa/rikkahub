package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管理写入门禁纯函数测试。
 *
 * 覆盖：无门禁（标准初始化/测试）默认放行；门禁放行/拒绝；以及每次调用读取最新门禁值，
 * 保证管理写入在提交时重新校验而不是使用捕获的旧布尔值。
 */
class SettingsManagementGateTest {

    @Test
    fun `missing gate allows management writes`() {
        assertTrue(isManagementWriteAllowedByGate(null))
    }

    @Test
    fun `open gate allows management writes`() {
        assertTrue(isManagementWriteAllowedByGate { true })
    }

    @Test
    fun `closed gate rejects management writes`() {
        assertFalse(isManagementWriteAllowedByGate { false })
    }

    @Test
    fun `gate is re-evaluated on every call`() {
        var allowed = true
        val gate = { allowed }

        assertTrue(isManagementWriteAllowedByGate(gate))

        allowed = false
        assertFalse(isManagementWriteAllowedByGate(gate))

        allowed = true
        assertTrue(isManagementWriteAllowedByGate(gate))
    }
}
