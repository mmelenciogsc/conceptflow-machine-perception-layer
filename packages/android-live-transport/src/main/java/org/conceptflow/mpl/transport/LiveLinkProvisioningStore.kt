// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** App-private identity/config files; only the public certificate is exportable through run-as. */
class LiveLinkProvisioningStore(private val context: Context) {
    private val directory: File
        get() = File(context.noBackupFilesDir, DIRECTORY_NAME)

    fun ensureIdentity(alias: String): AndroidTlsIdentity {
        val identity = AndroidKeystoreTlsIdentity().ensure(alias)
        val targetDirectory = directory.apply {
            require(isDirectory || mkdirs()) { "could not create app-private live-link directory" }
            require(setReadable(false, false) && setReadable(true, true)) {
                "could not restrict live-link directory"
            }
            require(setWritable(false, false) && setWritable(true, true)) {
                "could not restrict live-link directory"
            }
            require(setExecutable(false, false) && setExecutable(true, true)) {
                "could not restrict live-link directory"
            }
        }
        atomicWrite(File(targetDirectory, PUBLIC_CERTIFICATE_FILE), identity.publicCertificateDer)
        return identity
    }

    fun loadConfig(role: LiveLinkEndpointRole): LiveLinkPrivateConfig =
        File(directory, CONFIGURATION_FILE).inputStream().buffered().use { LiveLinkPrivateConfig.parse(it, role) }

    fun publicCertificateFile(): File = File(directory, PUBLIC_CERTIFICATE_FILE)

    override fun toString(): String = "LiveLinkProvisioningStore(path=<redacted>)"

    private fun atomicWrite(target: File, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PUBLIC_CERTIFICATE_BYTES) {
            "public certificate is outside its size bound"
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            require(temporary.setReadable(false, false) && temporary.setReadable(true, true)) {
                "could not restrict public-certificate file"
            }
            require(temporary.setWritable(false, false) && temporary.setWritable(true, true)) {
                "could not restrict public-certificate file"
            }
            require(temporary.renameTo(target)) { "could not commit public-certificate file" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "live-link"
        const val PUBLIC_CERTIFICATE_FILE = "public-identity.der"
        const val CONFIGURATION_FILE = "live-link.properties"
        private const val MAX_PUBLIC_CERTIFICATE_BYTES = 16 * 1024
    }
}
