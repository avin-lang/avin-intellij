package dev.avinlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AvinSourceReferenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a trap names a bare file, a diagnostic a full path`() {
        val trap = "avin: trap: assertion failed: always traps at main.avin:3:5"
        assertEquals(listOf(SourceReference(46, 59, "main.avin", 3, 5)), findSourceReferences(trap))
        assertEquals("main.avin:3:5", trap.substring(46, 59))
        val diagnostic = " --> /src/p/clamp.avin:17:5"
        assertEquals(listOf(SourceReference(5, 27, "/src/p/clamp.avin", 17, 5)), findSourceReferences(diagnostic))
        assertEquals(listOf(SourceReference(0, 16, "sub/d.lavin", 10, 2)), findSourceReferences("sub/d.lavin:10:2 x"))
    }

    @Test
    fun `other files and partial places are not references`() {
        assertEquals(emptyList<SourceReference>(), findSourceReferences("at main.c:3:5, main.avin:3, notavin:1:1, x.avinx:1:1"))
    }

    @Test
    fun `a bare name prefers the failing test's file, then the root, then the first below it`() {
        val root = temp.newFolder("p")
        val nested = File(root, "a/util.avin").apply { parentFile.mkdirs(); writeText("") }
        val top = File(root, "main.avin").apply { writeText("") }
        val other = File(root, "b/main.avin").apply { parentFile.mkdirs(); writeText("") }
        File(root, ".avin/build/hidden.avin").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(other, resolveSourceFile("main.avin", root, other))
        assertEquals(top, resolveSourceFile("main.avin", root))
        assertEquals(nested, resolveSourceFile("util.avin", root))
        assertEquals(nested, resolveSourceFile("a/util.avin", root))
        assertNull(resolveSourceFile("hidden.avin", root))
        assertNull(resolveSourceFile("missing.avin", root))
        assertEquals(top, resolveSourceFile(top.path, null))
        assertNull(resolveSourceFile("main.avin", null))
    }
}
