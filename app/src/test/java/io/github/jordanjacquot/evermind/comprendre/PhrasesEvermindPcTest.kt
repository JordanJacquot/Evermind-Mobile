package io.github.jordanjacquot.evermind.comprendre

import org.junit.Assert.assertTrue
import org.junit.Test

/** Les près de 3 000 phrases des catalogues d'Evermind PC : la lecture Kotlin doit donner exactement la même chose. */
class PhrasesEvermindPcTest {
    @Test fun memeLectureQueSurPc() {
        val json = javaClass.classLoader!!.getResourceAsStream("phrases-evermind-pc.json")!!.reader(Charsets.UTF_8).readText()
        val (total, erreurs) = ComparaisonPc.differences(json)
        println("Phrases comprises comme sur PC : ${total - erreurs.size} / $total")
        erreurs.take(40).forEach { println(it) }
        assertTrue("${erreurs.size} phrases lues autrement que sur PC (détail ci-dessus)", erreurs.isEmpty())
    }
}
