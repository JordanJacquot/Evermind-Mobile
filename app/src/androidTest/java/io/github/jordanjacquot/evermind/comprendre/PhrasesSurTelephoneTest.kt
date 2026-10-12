package io.github.jordanjacquot.evermind.comprendre

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Les mêmes phrases sur le téléphone : Android lit les expressions régulières autrement que la JVM (ICU). */
@RunWith(AndroidJUnit4::class)
class PhrasesSurTelephoneTest {
    @Test fun memeLectureQueSurPc() {
        val contexte = InstrumentationRegistry.getInstrumentation().context
        val json = contexte.assets.open("phrases-evermind-pc.json").reader(Charsets.UTF_8).readText()
        val (total, erreurs) = ComparaisonPc.differences(json)
        Log.i("Evermind", "Phrases comprises comme sur PC : ${total - erreurs.size} / $total")
        erreurs.take(40).forEach { Log.i("Evermind", it) }
        assertTrue("${erreurs.size} phrases lues autrement que sur PC :\n" + erreurs.take(10).joinToString("\n"), erreurs.isEmpty())
    }
}
