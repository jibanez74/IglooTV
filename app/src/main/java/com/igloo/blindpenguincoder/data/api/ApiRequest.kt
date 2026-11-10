package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** [path] (starting with `/`) on the API of the server the session points at. */
internal fun ServerUrlProvider.api(path: String): String = "${require().apiBaseUrl}$path"

/** [body] as the request's JSON payload. */
internal inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
