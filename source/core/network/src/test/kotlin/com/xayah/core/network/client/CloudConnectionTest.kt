package com.xayah.core.network.client

import com.xayah.core.model.CloudType
import com.xayah.core.model.SmbVersion
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.database.SMBExtra
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CloudConnectionTest {
    @Test
    fun failedHandshakesReleaseSecureConnections() {
        for (type in listOf(CloudType.SFTP, CloudType.SMB)) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 3_000
                val executor = Executors.newSingleThreadExecutor()
                val connection = executor.submit { server.accept().close() }
                try {
                    val entity = CloudEntity("test", type, server.inetAddress.hostAddress!!, "test", "test", "/backup", "", false)
                    val client = if (type == CloudType.SFTP) {
                        SFTPClientImpl(entity, SFTPExtra(server.localPort, ""))
                    } else {
                        SMBClientImpl(entity, SMBExtra("", server.localPort, "", listOf(SmbVersion.SMB_3_1_1)))
                    }
                    assertThrows(Exception::class.java) { client.connect() }
                    connection.get(5, TimeUnit.SECONDS)
                    val field = if (type == CloudType.SFTP) "sshClient" else "client"
                    assertNull(client.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(client))
                    client.disconnect()
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }
}
