package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Test

class FileLinksTest {

    private fun paths(text: String) = FileLinks.findPaths(text).filter { it.kind == FileLinks.Kind.PATH }.map { it.of(text) }
    private fun names(text: String) = FileLinks.findPaths(text).filter { it.kind == FileLinks.Kind.NAME }.map { it.of(text) }

    @Test
    fun `path at the end of a sentence loses the full stop`() {
        val t = "It's named IMG_20260912_101512.jpg and is located in /storage/emulated/0/DCIM/Camera/IMG_20260912_101512.jpg."
        assertEquals(listOf("/storage/emulated/0/DCIM/Camera/IMG_20260912_101512.jpg"), paths(t))
    }

    @Test
    fun `directory paths and bare roots are not links`() {
        assertEquals(emptyList<String>(), paths("located in /storage/emulated/0/DCIM/Camera/."))
        assertEquals(emptyList<String>(), paths("under /sdcard there are files"))
    }

    @Test
    fun `markdown code, parentheses and commas end a path`() {
        val t = "Two files: `/sdcard/Documents/demo/hotel-receipt-austin.md`, and (/storage/emulated/0/Documents/demo/grocery-list.txt)."
        assertEquals(
            listOf("/sdcard/Documents/demo/hotel-receipt-austin.md", "/storage/emulated/0/Documents/demo/grocery-list.txt"),
            paths(t)
        )
    }

    @Test
    fun `tool result listing yields one hit per line`() {
        val t = """
            1. IMG_20260910_101122.jpg — image, 251 kB
               path: /storage/emulated/0/DCIM/Camera/IMG_20260910_101122.jpg
            2. IMG_20260912_101512.jpg — image, 276 kB
               path: /storage/emulated/0/DCIM/Camera/IMG_20260912_101512.jpg
        """.trimIndent()
        assertEquals(2, paths(t).size)
    }

    @Test
    fun `bare names with known extensions are hits, once, and directories are collected`() {
        val t = "It's named IMG_20260912_101512.jpg and is located in /storage/emulated/0/DCIM/Camera/."
        assertEquals(listOf("IMG_20260912_101512.jpg"), names(t))
        assertEquals(emptyList<String>(), paths(t))
        assertEquals(listOf("/storage/emulated/0/DCIM/Camera"), FileLinks.directoriesIn(t))
    }

    @Test
    fun `a name inside a full path is not reported twice`() {
        val t = "path: /storage/emulated/0/DCIM/Camera/IMG_20260910_101122.jpg"
        assertEquals(1, FileLinks.findPaths(t).size)
        assertEquals(FileLinks.Kind.PATH, FileLinks.findPaths(t).single().kind)
    }

    @Test
    fun `list of names and ordinary words`() {
        val t = "two photos of waterfalls: IMG_20260824_154801.jpg and IMG_20260712_143012.jpg. Version 2.5 is fine."
        assertEquals(listOf("IMG_20260824_154801.jpg", "IMG_20260712_143012.jpg"), names(t))
    }

    @Test
    fun `hit offsets point at the text`() {
        val t = "see /sdcard/Documents/demo/grocery-list.txt now"
        val hit = FileLinks.findPaths(t).single()
        assertEquals(4, hit.start)
        assertEquals(t.indexOf(" now"), hit.end)
    }
}
