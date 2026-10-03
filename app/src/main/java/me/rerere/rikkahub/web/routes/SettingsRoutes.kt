package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ForbiddenException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.UpdateAssistantModelRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantReasoningLevelRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantMcpServersRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantInjectionsRequest
import me.rerere.rikkahub.web.dto.UpdateBuiltInToolRequest
import me.rerere.rikkahub.web.dto.UpdateFavoriteModelsRequest
import me.rerere.rikkahub.web.dto.UpdateSearchEnabledRequest
import me.rerere.rikkahub.web.dto.UpdateSearchServiceRequest
import java.util.Locale

fun Route.settingsRoutes(
    settingsStore: SettingsStore,
    isManagementAllowed: () -> Boolean = { false }
) {
    // Re-evaluated immediately before every settings mutation so a request admitted just
    // before a family-mode relock cannot still commit after the gate closes.
    fun requireManagementAccess() {
        if (!isManagementAllowed()) {
            throw ForbiddenException("Management is not allowed in the current mode")
        }
    }

    route("/settings") {
        post("/assistant") {
            val request = call.receive<UpdateAssistantRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")

            requireManagementAccess()
            if (!settingsStore.updateAssistantManagement(assistantId)) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/model") {
            val request = call.receive<UpdateAssistantModelRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")
            val modelId = request.modelId.toUuid("modelId")

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            val model = settings.findModelById(modelId)
                ?: throw NotFoundException("Model not found")
            if (model.type != ModelType.CHAT) {
                throw BadRequestException("modelId must be a chat model")
            }

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(chatModelId = modelId)
                        } else {
                            assistant
                        }
                    }
                )
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/thinking-budget") {
            val request = call.receive<UpdateAssistantReasoningLevelRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(reasoningLevel = request.reasoningLevel)
                        } else {
                            assistant
                        }
                    }
                )
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/mcp") {
            val request = call.receive<UpdateAssistantMcpServersRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            val validServerIds = settings.mcpServers.map { it.id }.toSet()
            val requestedServerIds = request.mcpServerIds.map { it.toUuid("mcpServerIds") }.toSet()
            if (!validServerIds.containsAll(requestedServerIds)) {
                throw BadRequestException("mcpServerIds contains unknown server id")
            }

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(mcpServers = requestedServerIds)
                        } else {
                            assistant
                        }
                    }
                )
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/injections") {
            val request = call.receive<UpdateAssistantInjectionsRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val requestedModeInjectionIds =
                request.modeInjectionIds.map { it.toUuid("modeInjectionIds") }.toSet()
            if (!validModeInjectionIds.containsAll(requestedModeInjectionIds)) {
                throw BadRequestException("modeInjectionIds contains unknown injection id")
            }

            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val requestedLorebookIds = request.lorebookIds.map { it.toUuid("lorebookIds") }.toSet()
            if (!validLorebookIds.containsAll(requestedLorebookIds)) {
                throw BadRequestException("lorebookIds contains unknown lorebook id")
            }

            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val requestedQuickMessageIds =
                request.quickMessageIds.map { it.toUuid("quickMessageIds") }.toSet()
            if (!validQuickMessageIds.containsAll(requestedQuickMessageIds)) {
                throw BadRequestException("quickMessageIds contains unknown quick message id")
            }

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(
                                modeInjectionIds = requestedModeInjectionIds,
                                lorebookIds = requestedLorebookIds,
                                quickMessageIds = requestedQuickMessageIds,
                            )
                        } else {
                            assistant
                        }
                    }
                )
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/enabled") {
            val request = call.receive<UpdateSearchEnabledRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(enableWebSearch = request.enabled)
                        } else {
                            assistant
                        }
                    }
                )
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/service") {
            val request = call.receive<UpdateSearchServiceRequest>()

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                if (current.searchServices.isEmpty()) {
                    throw BadRequestException("No search services configured")
                }
                if (request.index !in current.searchServices.indices) {
                    throw BadRequestException("search service index out of range")
                }
                current.copy(searchServiceSelected = request.index)
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/model/built-in-tool") {
            val request = call.receive<UpdateBuiltInToolRequest>()
            val modelId = request.modelId.toUuid("modelId")
            val targetTool = parseBuiltInTool(request.tool)

            requireManagementAccess()
            val updated = settingsStore.updateManagement { current ->
                val model = current.findModelById(modelId)
                    ?: throw NotFoundException("Model not found")
                if (model.type != ModelType.CHAT) {
                    throw BadRequestException("modelId must be a chat model")
                }

                val updatedModel = model.copy(
                    tools = if (request.enabled) {
                        model.tools + targetTool
                    } else {
                        model.tools - targetTool
                    }
                )

                current.copy(
                    providers = current.providers.map { provider ->
                        provider.editModel(updatedModel)
                    }
                )
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/favorite-models") {
            val request = call.receive<UpdateFavoriteModelsRequest>()
            val favoriteModelIds = request.modelIds.map { it.toUuid("modelId") }

            requireManagementAccess()
            val updated = settingsStore.updateManagement { settings ->
                settings.copy(favoriteModels = favoriteModelIds)
            }
            if (!updated) {
                throw ForbiddenException("Management is not allowed in the current mode")
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

private fun parseBuiltInTool(tool: String): BuiltInTools {
    return when (tool.trim().lowercase(Locale.ROOT)) {
        "search" -> BuiltInTools.Search
        "url_context", "url-context", "urlcontext" -> BuiltInTools.UrlContext
        "image_generation", "image-generation", "imagegeneration" -> BuiltInTools.ImageGeneration
        else -> throw BadRequestException("Unsupported built-in tool")
    }
}
