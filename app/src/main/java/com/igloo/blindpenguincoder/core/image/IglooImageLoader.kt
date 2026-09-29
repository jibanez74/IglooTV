package com.igloo.blindpenguincoder.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult
import com.igloo.blindpenguincoder.core.network.DeviceCredentialSource
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.isIglooServerUrl

/**
 * Attaches the active profile's bearer token to image requests, because the backend's image
 * proxy is authenticated like every other endpoint. A Coil interceptor rather than an OkHttp
 * one: the credential sits behind a suspending source, which an OkHttp interceptor could only
 * reach by blocking a dispatcher thread.
 *
 * A failed poster stays a failed poster — these requests bypass the Ktor client, so an image
 * 401 renders a placeholder and can never sign a profile out.
 */
private class BearerImageInterceptor(
    private val credentials: DeviceCredentialSource,
    private val serverUrl: ServerUrlProvider,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String
        if (!isIglooServerUrl(url, serverUrl.current.value?.origin)) return chain.proceed()
        val token = credentials.current()?.token ?: return chain.proceed()
        val request = chain.request.newBuilder()
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $token").build())
            .build()
        return chain.withRequest(request).proceed()
    }
}

fun createIglooImageLoader(
    context: Context,
    credentials: DeviceCredentialSource,
    serverUrl: ServerUrlProvider,
): ImageLoader = ImageLoader.Builder(context)
    .components { add(BearerImageInterceptor(credentials, serverUrl)) }
    .build()
