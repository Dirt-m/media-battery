package app.mediabattery

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No dash punctuation in user facing copy: no em dash, no en dash, no hyphen standing in
 * for one. Every string the app shows lives in these files, so this is where to check.
 */
class StringsCopyTest {

    @Test
    fun `no dash punctuation in any string resource`() {
        val offenders = valuesFiles().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.contains('—') || line.contains('–') || line.contains(" - ") }
                .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
        }
        assertTrue("Dash punctuation in copy:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the string files are actually being read`() {
        assertTrue("no values/*.xml found", valuesFiles().isNotEmpty())
    }

    private fun valuesFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val res = File(dir, "app/src/main/res/values").takeIf { it.isDirectory }
                ?: File(dir, "src/main/res/values").takeIf { it.isDirectory }
            if (res != null) return res.listFiles { f -> f.extension == "xml" }?.toList().orEmpty()
            dir = dir.parentFile
        }
        return emptyList()
    }
}
