package me.rerere.rikkahub.data.ai.mcp

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 家人模式 MCP 助手上下文修正的纯函数测试。
 *
 * 覆盖：两个绑定不同 MCP 的助手对全局当前助手的独立投影、禁用服务/禁用工具、
 * 未绑定服务，以及枚举不修改来源配置。
 */
class AvailableMcpToolsTest {

    private fun server(
        id: Uuid = Uuid.random(),
        name: String,
        enable: Boolean = true,
        tools: List<McpTool> = listOf(McpTool(name = "tool")),
    ): McpServerConfig.StreamableHTTPServer = McpServerConfig.StreamableHTTPServer(
        id = id,
        commonOptions = McpCommonOptions(enable = enable, name = name, tools = tools),
        url = "https://example.com/$name",
    )

    private fun tool(name: String, enable: Boolean = true): McpTool =
        McpTool(name = name, enable = enable)

    @Test
    fun `enumeration follows the passed assistant instead of global selection`() {
        val serverA = server(name = "server-a", tools = listOf(tool("a-1"), tool("a-2")))
        val serverB = server(name = "server-b", tools = listOf(tool("b-1")))
        val assistantA = Assistant(id = Uuid.random(), name = "A", mcpServers = setOf(serverA.id))
        val assistantB = Assistant(id = Uuid.random(), name = "B", mcpServers = setOf(serverB.id))
        val settings = Settings(
            assistantId = assistantA.id,
            assistants = listOf(assistantA, assistantB),
            mcpServers = listOf(serverA, serverB),
        )

        // 全局当前助手明确指向 A。
        assertEquals(assistantA.id, settings.getCurrentAssistant().id)

        // 显式传入 B 时必须使用 B 的绑定，而不是全局选中的 A。
        val forB = availableMcpTools(settings.mcpServers, assistantB)
        assertEquals(setOf(serverB.id), forB.map { it.first }.toSet())
        assertEquals(listOf("server-b" to "b-1"), forB.map { it.second to it.third.name })
        assertTrue(forB.none { it.first == serverA.id })

        val forA = availableMcpTools(settings.mcpServers, assistantA)
        assertEquals(setOf(serverA.id), forA.map { it.first }.toSet())
        assertEquals(setOf("a-1", "a-2"), forA.map { it.third.name }.toSet())

        // 无参/默认调用仍按全局当前助手投影，与显式传入该助手一致。
        val globalProjection = availableMcpTools(settings.mcpServers, settings.getCurrentAssistant())
        assertEquals(forA.map { it.third.name }.toSet(), globalProjection.map { it.third.name }.toSet())
    }

    @Test
    fun `disabled servers disabled tools and unbound servers are excluded`() {
        val bound = server(
            name = "bound",
            tools = listOf(tool("on"), tool("off", enable = false)),
        )
        val disabledServer = server(
            name = "disabled-server",
            enable = false,
            tools = listOf(tool("hidden")),
        )
        val unbound = server(name = "unbound", tools = listOf(tool("orphan")))
        val assistant = Assistant(
            id = Uuid.random(),
            mcpServers = setOf(bound.id, disabledServer.id),
        )

        val result = availableMcpTools(listOf(bound, disabledServer, unbound), assistant)

        assertEquals(1, result.size)
        val (serverId, serverName, mcpTool) = result.single()
        assertEquals(bound.id, serverId)
        assertEquals("bound", serverName)
        assertEquals("on", mcpTool.name)
    }

    @Test
    fun `enumeration does not mutate source settings and returns independent list`() {
        val tools = mutableListOf(tool("one"), tool("two"))
        val server = McpServerConfig.StreamableHTTPServer(
            id = Uuid.random(),
            commonOptions = McpCommonOptions(name = "srv", tools = tools),
            url = "https://example.com/srv",
        )
        val servers = mutableListOf(server)
        val assistant = Assistant(id = Uuid.random(), mcpServers = setOf(server.id))

        val projected = availableMcpTools(servers, assistant).toMutableList()
        projected.clear()

        // 结果列表可被调用方独立修改，来源服务/工具配置保持不变。
        assertEquals(listOf("one", "two"), tools.map { it.name })
        assertEquals(listOf(server), servers)
        assertEquals(setOf(server.id), assistant.mcpServers)
        assertEquals(2, availableMcpTools(servers, assistant).size)
    }

    @Test
    fun `shared servers are projected consistently for multiple assistants`() {
        val shared = server(name = "shared", tools = listOf(tool("shared-tool")))
        val assistantA = Assistant(id = Uuid.random(), mcpServers = setOf(shared.id))
        val assistantB = Assistant(id = Uuid.random(), mcpServers = setOf(shared.id))

        // 先为 B 求值不应污染随后为 A 的求值。
        val forB = availableMcpTools(listOf(shared), assistantB)
        val forA = availableMcpTools(listOf(shared), assistantA)

        assertEquals(listOf("shared-tool"), forB.map { it.third.name })
        assertEquals(listOf("shared-tool"), forA.map { it.third.name })
    }
}
