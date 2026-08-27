// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.Closeable
import java.net.InetAddress

/** Resolves the current data-plane endpoint without weakening peer authentication. */
fun interface LiveLinkEndpointResolver {
    @Throws(Exception::class)
    fun awaitAddress(timeoutMillis: Long): InetAddress
}

class StaticLiveLinkEndpointResolver(
    private val address: InetAddress,
) : LiveLinkEndpointResolver {
    override fun awaitAddress(timeoutMillis: Long): InetAddress {
        require(timeoutMillis > 0L)
        return address
    }
}

internal fun LiveLinkEndpointResolver.closeIfOwned() {
    if (this is Closeable) runCatching { close() }
}
