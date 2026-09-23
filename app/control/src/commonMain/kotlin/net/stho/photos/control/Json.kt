package net.stho.photos.control

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/**
 * Hand-rolled JSON, deliberately, for both control servers: the payloads are scalars and a few
 * lists, and a serialization dependency would be a second one for the sake of a development
 * surface.
 */
internal fun String.json(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

internal suspend fun ApplicationCall.json(body: String) =
    respondText(body, ContentType.Application.Json)

internal suspend fun ApplicationCall.fail(status: HttpStatusCode, why: String) =
    respondText(why, ContentType.Text.Plain, status)
