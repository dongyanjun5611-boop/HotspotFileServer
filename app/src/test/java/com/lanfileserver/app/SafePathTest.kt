package com.lanfileserver.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafePathTest {
    @Test
    fun normalizesRootAndNestedPaths() {
        assertEquals("", SafePath.normalize("/"))
        assertEquals("照片/旅行", SafePath.normalize("/照片/旅行/"))
    }

    @Test
    fun rejectsTraversalSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            SafePath.normalize("照片/../私密")
        }
    }

    @Test
    fun sanitizesUploadedNames() {
        assertEquals("报告_最终.pdf", SafePath.sanitizeName(" 报告/最终.pdf "))
        assertEquals("图片_1.jpg", SafePath.sanitizeName("图片\\1.jpg"))
    }

    @Test
    fun joinsWithoutLeadingSlash() {
        assertEquals("照片/旅行/a.jpg", SafePath.join("照片/旅行", "a.jpg"))
        assertEquals("a.jpg", SafePath.join("", "a.jpg"))
    }
}
