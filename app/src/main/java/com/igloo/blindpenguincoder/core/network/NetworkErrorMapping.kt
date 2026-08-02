package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.error.AppError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Maps a transport failure onto [AppError]. Engines wrap the real cause, so the
 * whole chain is inspected. Timeouts are checked first: [HttpRequestTimeoutException]
 * and [ConnectTimeoutException] are themselves [IOException]s.
 */
internal fun Throwable.toTransportError(): AppError {
    val causes = generateSequence<Throwable>(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .toList()
    return when {
        causes.any { it.isTimeout() } -> AppError.Timeout
        causes.any { it is SSLException || it is CertificateException } -> AppError.TlsVerification
        causes.any { it is IOException } -> AppError.Network
        else -> AppError.Unexpected(message)
    }
}

private fun Throwable.isTimeout(): Boolean =
    this is HttpRequestTimeoutException ||
        this is ConnectTimeoutException ||
        this is SocketTimeoutException

// Bounds a self-referencing cause chain.
private const val MAX_CAUSE_DEPTH = 8
