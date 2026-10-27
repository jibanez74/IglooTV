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
import com.igloo.blindpenguincoder.images.serverImageUrl

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

/**
 * Resolves a server path against the active server's origin, the way a browser resolves a
 * relative URL. Album covers and musician photos reach the UI as the API returns them, which
 * with the server's Download images setting on is its own `/api/static/...` copy rather than an
 * absolute Spotify or Apple URL. Ahead of [BearerImageInterceptor], so that one sees a
 * same-origin URL and attaches the token; absolute URLs pass through untouched.
 */
private class ServerPathInterceptor(
    private val serverUrl: ServerUrlProvider,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = resolveServerPath(chain.request.data, serverUrl.current.value?.origin)
            ?: return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(url).build()).proceed()
    }
}

/** What [ServerPathInterceptor] loads instead of [data]; null leaves the request as it is. */
internal fun resolveServerPath(data: Any?, origin: String?): String? {
    val path = data as? String ?: return null
    if (origin == null || !path.startsWith("/")) return null
    return serverImageUrl(origin, path)
}

fun createIglooImageLoader(
    context: Context,
    credentials: DeviceCredentialSource,
    serverUrl: ServerUrlProvider,
): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(ServerPathInterceptor(serverUrl))
        add(BearerImageInterceptor(credentials, serverUrl))
    }
    .build()
