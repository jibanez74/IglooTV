package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse

/**
 * A route that answers with the shared envelope, decoded to its `data`; a success without `data`
 * is a defect. [noun] names the route in that defect's message.
 */
internal suspend inline fun <reified T> envelopeData(
    noun: String,
    noinline request: suspend () -> HttpResponse,
): ApiResult<T> = safeApiCall(
    request = request,
    decode = { response ->
        response.body<ApiEnvelope<T>>().data ?: error("Missing data in $noun response")
    },
)
