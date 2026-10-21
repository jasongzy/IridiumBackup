package com.xayah.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathUtilTest {
    @Test
    fun backupPathsRequireAValidPackageIdentity() {
        assertEquals("com.Revsoft.Wabbitemu/user_10@1234", PathUtil.getPackageArchiveRelativeDir("com.Revsoft.Wabbitemu", 10, 1234))
        assertEquals("android/user_0", PathUtil.getPackageArchiveRelativeDir("android", 0, 0))
        listOf(null, "", "..", "../../Download/victim", "com.example/..", "com..example", "com.example\\file").forEach {
            assertNull(PathUtil.getPackageArchiveRelativeDir(it, 0, 1234))
        }
        assertNull(PathUtil.getPackageArchiveRelativeDir("com.example", -1, 1234))
        assertNull(PathUtil.getPackageArchiveRelativeDir("com.example", 0, -1))
    }

    @Test
    fun parentPathRemovesOnlyTheLastSegment() {
        assertEquals("/a/b", PathUtil.getParentPath("/a/b/b"))
        assertEquals("/a/b", PathUtil.getParentPath("/a/b/b/"))
        assertEquals("/", PathUtil.getParentPath("/a"))
        assertEquals("", PathUtil.getParentPath("/"))
        assertEquals("", PathUtil.getParentPath("name"))
    }
}
