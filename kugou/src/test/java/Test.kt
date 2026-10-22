import com.zionhuang.kugou.KuGou
import com.zionhuang.kugou.KuGou.generateKeyword
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

@Ignore("Requires network access to kugou.com; run locally with CGO-less JVM only")
class Test {
    @Test
    fun test() = runBlocking {
        val candidates = KuGou.getLyricsCandidate(generateKeyword("千年以後 (After A Thousand Years)", "陳零九"), 285)
        assertTrue(candidates != null)
        assertTrue(KuGou.getLyrics("楊丞琳", "點水", 259).isSuccess)
    }
}
