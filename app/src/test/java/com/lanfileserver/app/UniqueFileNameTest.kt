package com.lanfileserver.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueFileNameTest {
    @Test
    fun usesRequestedNameWhenAvailable() {
        assertEquals("archive.tar.gz", UniqueFileName.choose("archive.tar.gz", setOf("other.zip")))
    }

    @Test
    fun usesFirstFreeSuffixAndPreservesExtension() {
        assertEquals(
            "archive.tar (2).gz",
            UniqueFileName.choose(
                "archive.tar.gz",
                setOf("archive.tar.gz", "archive.tar (1).gz", "archive.tar (3).gz"),
            ),
        )
    }

    @Test
    fun handlesNamesWithoutExtensions() {
        assertEquals("photo (1)", UniqueFileName.choose("photo", setOf("photo")))
        assertEquals(".hidden (1)", UniqueFileName.choose(".hidden", setOf(".hidden")))
    }
}
