package com.xayah.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PathUtilTest {
    @Test
    fun parentPathRemovesOnlyTheLastSegment() {
        assertEquals("/a/b", PathUtil.getParentPath("/a/b/b"))
        assertEquals("/a/b", PathUtil.getParentPath("/a/b/b/"))
        assertEquals("/", PathUtil.getParentPath("/a"))
        assertEquals("", PathUtil.getParentPath("/"))
        assertEquals("", PathUtil.getParentPath("name"))
    }
}
