package com.xayah.core.network.client

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SmbPath
import com.hierynomus.smbj.connection.ConnectionContext
import com.hierynomus.smbj.connection.NegotiatedProtocol
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.TreeConnect
import com.xayah.core.model.CloudType
import com.xayah.core.model.SmbVersion
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SMBExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SMBClientTest {
    @Test
    fun listsBackupDirectoriesWithinTheConnectedShare() {
        val config = SmbConfig.createDefaultConfig()
        val context = ConnectionContext::class.java.getDeclaredConstructor(
            UUID::class.java, String::class.java, Int::class.javaPrimitiveType, SmbConfig::class.java,
        ).apply { isAccessible = true }.newInstance(UUID.randomUUID(), "localhost", 445, config)
        ConnectionContext::class.java.getDeclaredField("negotiatedProtocol").apply { isAccessible = true }.set(
            context, NegotiatedProtocol(SMB2Dialect.SMB_2_1, 65_536, 65_536, 65_536, false),
        )
        val session = Session(null, config, AuthenticationContext.anonymous(), null, PathResolver.LOCAL, null, null)
        val path = SmbPath("localhost", "backups")
        val tree = TreeConnect(1, path, session, emptySet(), config, context, null, emptySet(), emptySet())
        val listed = mutableListOf<String>()
        val disk = object : DiskShare(path, tree, PathResolver.LOCAL) {
            override fun list(path: String): List<FileIdBothDirectoryInformation> {
                listed.add(path)
                return emptyList()
            }

            override fun folderExists(path: String) = true
        }
        val entity = CloudEntity("test", CloudType.SMB, "localhost", "", "", "/backup", "", false)
        val client = SMBClientImpl(entity, SMBExtra("backups", 445, "", listOf(SmbVersion.SMB_2_1)))
        SMBClientImpl::class.java.getDeclaredField("share").apply { isAccessible = true }.set(client, disk)

        val paths = listOf("", "/IridiumBackup/apps/com.example", "backups/nested")
        for (directory in paths) {
            val children = client.listFiles(directory)
            assertTrue(children.files.isEmpty() && children.directories.isEmpty())
        }
        assertTrue(client.walkFileTree("/IridiumBackup/apps").isEmpty())
        assertEquals(paths + "/IridiumBackup/apps", listed)
    }
}
