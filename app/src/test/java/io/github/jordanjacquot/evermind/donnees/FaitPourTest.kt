package io.github.jordanjacquot.evermind.donnees

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.rappels.ReglagesRappels
import io.github.jordanjacquot.evermind.rappels.rappelsEntre
import io.github.jordanjacquot.evermind.ui.fiche.FicheViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime

/** Une base en mémoire, à la place de Room (pour les écrans et le dépôt). */
class FausseBase(depart: List<RendezVous> = emptyList()) : RendezVousDao {
    val donnees = MutableStateFlow(depart.associateBy { it.id })
    override fun tous(): Flow<List<RendezVous>> = donnees.map { it.values.sortedBy { r -> r.quand } }
    override suspend fun liste(): List<RendezVous> = donnees.value.values.toList()
    override fun parId(id: String): Flow<RendezVous?> = donnees.map { it[id] }
    override suspend fun enregistrer(rendezVous: RendezVous) { donnees.value = donnees.value + (rendezVous.id to rendezVous) }
    override suspend fun supprimer(id: String) { donnees.value = donnees.value - id }
}

/** La table des rendez-vous dans un schéma de Room (schemas/.../<version>.json). */
private fun schema(f: File): JsonObject =
    Json.parseToJsonElement(f.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.single().jsonObject
private fun JsonObject.texte(cle: String): String = this[cle]?.jsonPrimitive?.content ?: ""

/** Les écrans enregistrent en arrière-plan (viewModelScope, sans fil principal sur PC) : on attend au plus 5 s. */
fun attendre(condition: () -> Boolean) {
    val fin = System.currentTimeMillis() + 5_000
    while (!condition()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(10) }
}

/** Les réglages des rappels sans Android (jamais lus : les rendez-vous des tests ont leurs propres rappels). */
fun reglagesSansAndroid(): ReglagesRappels {
    val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return (f.get(null) as sun.misc.Unsafe).allocateInstance(ReglagesRappels::class.java) as ReglagesRappels
}

/** « C'est fait » sur une chose qui revient : seulement cette fois-là (constat 5 de l'audit du 25/10). */
class FaitPourTest {
    private val maintenant = LocalDateTime.of(2026, 10, 13, 19, 10)   // un mardi
    private val poubelles = RendezVous(titre = "Poubelles", sorte = Sorte.A_FAIRE, quand = LocalDateTime.of(2026, 10, 6, 19, 0),
        repetition = Repetition("semaine", jour = 2).versJson(), rappels = listOf("m0"))

    @Test fun faitUneFoisNeCoupeQueCetteFois() {
        val r = reglagesSansAndroid()
        val de = maintenant.minusHours(1)
        // (sorties la semaine dernière)
        val serie = poubelles.copy(faitPour = listOf("2026-10-06"))
        val avant = rappelsEntre(listOf(serie), de, de.plusDays(60), r)
        val fait = serie.avecFait(serie.foisAFaire(maintenant), true, maintenant.toLocalDate())
        assertEquals(listOf("2026-10-06", "2026-10-13"), fait.faitPour)
        assertFalse(fait.fait)
        val apres = rappelsEntre(listOf(fait), de, de.plusDays(60), r)
        assertEquals("un rappel de moins (celui du 13), les autres restent", avant.size - 1, apres.size)
        assertTrue(apres.none { it.fois.toLocalDate() == LocalDate.of(2026, 10, 13) })
        assertTrue(apres.any { it.fois.toLocalDate() == LocalDate.of(2026, 10, 20) })
    }

    @Test fun laFoisAFaireEstCommenceeJamaisAVenir() {
        val cachets = RendezVous(titre = "Cachets", sorte = Sorte.A_FAIRE, quand = LocalDateTime.of(2026, 10, 1, 21, 0),
            repetition = Repetition("jour").versJson())
        // pris avant-hier, pas hier : à 9 h, c'est celui d'hier soir (jamais celui de ce soir)
        val avantHier = cachets.avecFait(LocalDateTime.of(2026, 10, 11, 21, 0), true, LocalDate.of(2026, 10, 13))
        assertEquals(LocalDateTime.of(2026, 10, 12, 21, 0), avantHier.foisAFaire(LocalDateTime.of(2026, 10, 13, 9, 0)))
        // hier aussi : à 9 h tout est coché (la dernière, rien de nouveau) ; à 20 h 45, celui de ce soir
        val hier = avantHier.avecFait(LocalDateTime.of(2026, 10, 12, 21, 0), true, LocalDate.of(2026, 10, 13))
        assertEquals(LocalDateTime.of(2026, 10, 12, 21, 0), hier.foisAFaire(LocalDateTime.of(2026, 10, 13, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 13, 21, 0), hier.foisAFaire(LocalDateTime.of(2026, 10, 13, 20, 45)))
        // jamais coché : la plus récente déjà commencée (choix de Jordan, comme sur PC), celui d'hier soir
        assertEquals(LocalDateTime.of(2026, 10, 12, 21, 0), cachets.foisAFaire(LocalDateTime.of(2026, 10, 13, 9, 0)))
        // à 20 h 45 (ce soir commence dans 15 min), jamais coché : celui de ce soir
        assertEquals(LocalDateTime.of(2026, 10, 13, 21, 0), cachets.foisAFaire(LocalDateTime.of(2026, 10, 13, 20, 45)))
        // à 20 h 15 (plus de 30 min avant) : encore celui d'hier soir, jamais celui de ce soir
        assertEquals(LocalDateTime.of(2026, 10, 12, 21, 0), cachets.foisAFaire(LocalDateTime.of(2026, 10, 13, 20, 15)))
        // pas encore commencée : rien à cocher
        assertNull(cachets.foisAFaire(LocalDateTime.of(2026, 9, 30, 12, 0)))
        // les fois cochées de plus de 90 jours sont oubliées
        val vieux = hier.avecFait(LocalDateTime.of(2027, 2, 1, 21, 0), true, LocalDate.of(2027, 2, 1))
        assertEquals(listOf("2027-02-01"), vieux.faitPour)
    }

    /** Les poubelles du mardi, jamais cochées : dites le mardi soir ou le mercredi, c'est la fois de ce mardi. */
    @Test fun laFoisLaPlusRecenteCommencee() {
        assertEquals(LocalDateTime.of(2026, 10, 13, 19, 0), poubelles.foisAFaire(maintenant))
        assertEquals(LocalDateTime.of(2026, 10, 13, 19, 0), poubelles.foisAFaire(LocalDateTime.of(2026, 10, 14, 10, 0)))
        // ce mardi déjà coché : celle d'avant, pas encore cochée
        val mardi = poubelles.avecFait(LocalDateTime.of(2026, 10, 13, 19, 0), true, maintenant.toLocalDate())
        assertEquals(LocalDateTime.of(2026, 10, 6, 19, 0), mardi.foisAFaire(LocalDateTime.of(2026, 10, 14, 10, 0)))
    }

    @Test fun unRendezVousUniqueGardeFait() {
        val courses = RendezVous(titre = "Courses", sorte = Sorte.A_FAIRE, quand = maintenant)
        val fait = courses.avecFait(null, true)
        assertTrue(fait.fait)
        assertTrue(fait.estFait(null))
        assertTrue(rappelsEntre(listOf(fait.copy(rappels = listOf("m0"))), maintenant.minusHours(1), maintenant.plusDays(1), reglagesSansAndroid()).isEmpty())
    }

    @Test fun laFicheCocheLaFoisMontree() = runBlocking {
        val base = FausseBase(listOf(poubelles))
        val vm = FicheViewModel(DepotRendezVous(base), poubelles.id)
        val ecran = launch(Dispatchers.Default) { vm.rendezVous.collect {} }
        attendre { vm.rendezVous.value != null }
        vm.basculerFait(LocalDateTime.of(2026, 10, 20, 19, 0))
        attendre { base.donnees.value.getValue(poubelles.id).faitPour.isNotEmpty() }
        val r = base.donnees.value.getValue(poubelles.id)
        assertEquals(listOf("2026-10-20"), r.faitPour)
        assertFalse(r.fait)
        ecran.cancel()
    }

    /**
     * La migration 1 vers 2 sur une vraie base SQLite, créée comme Room crée la version 1 (schemas/.../1.json) :
     * la table obtenue est celle que Room attend en version 2 (2.json), et l'ancien « fait » d'une série est repris.
     */
    @Test fun migrationDe1a2() {
        val schemas = File("schemas/io.github.jordanjacquot.evermind.donnees.BaseEvermind")
        val v1 = schema(File(schemas, "1.json")).texte("createSql").replace("\${TABLE_NAME}", "rendez_vous")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { cx ->
            cx.createStatement().use { it.execute(v1) }
            fun inserer(id: String, quand: String, rep: String?, fait: Int) = cx.prepareStatement(
                "INSERT INTO rendez_vous (id, titre, sorte, quand, journee, fin, repetition, lieu, avec, lien, notes, aPreparer, rappels, important, fait, naissance, cree) " +
                    "VALUES (?, 'x', 'rappel', ?, 0, NULL, ?, '', '[]', '', '', '[]', NULL, 0, ?, NULL, '2026-10-01T10:00')"
            ).use { s -> s.setString(1, id); s.setString(2, quand); s.setString(3, rep); s.setInt(4, fait); s.executeUpdate() }
            inserer("serie-faite", "2026-10-06T19:00", Repetition("semaine", jour = 2).versJson(), 1)
            inserer("serie", "2026-10-06T19:00", Repetition("semaine", jour = 2).versJson(), 0)
            inserer("unique-fait", "2026-10-10T10:00", null, 1)
            inserer("serie-pas-commencee", "2026-11-03T19:00", Repetition("semaine", jour = 2).versJson(), 1)
            inserer("date-abimee", "abimee", Repetition("jour").versJson(), 1)

            Migrations.de1a2(object : BaseSql {
                override fun executer(sql: String, args: Array<Any?>) {
                    cx.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.executeUpdate() }
                }
                override fun lignes(sql: String): List<List<String?>> = cx.createStatement().use { st ->
                    st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
                }
            }, maintenant)

            // La table : les colonnes de la version 2, comme Room les vérifie à l'ouverture
            val attendu = schema(File(schemas, "2.json"))["fields"]!!.jsonArray.map { f ->
                val o = f.jsonObject
                listOf(o.texte("columnName"), o.texte("affinity"), (o["notNull"]?.jsonPrimitive?.boolean ?: false).toString(), o.texte("defaultValue"))
            }.toSet()
            val obtenu = cx.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(rendez_vous)").use { rs ->
                    buildSet { while (rs.next()) add(listOf(rs.getString("name"), rs.getString("type"), (rs.getInt("notnull") == 1).toString(), rs.getString("dflt_value") ?: "")) }
                }
            }
            assertEquals(attendu, obtenu)

            val lu = cx.createStatement().use { st ->
                st.executeQuery("SELECT id, fait, faitPour FROM rendez_vous").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2) to rs.getString(3)) } }
            }
            assertEquals("une série cochée : la dernière fois passée seulement", 0 to "[\"2026-10-13\"]", lu["serie-faite"])
            assertEquals(0 to "[]", lu["serie"])
            assertEquals("un rendez-vous unique fait reste fait", 1 to "[]", lu["unique-fait"])
            assertEquals("pas encore commencée : sa première fois", 0 to "[\"2026-11-03\"]", lu["serie-pas-commencee"])
            assertEquals("une date illisible : laissé tel quel", 1 to "[]", lu["date-abimee"])
        }
    }

    @Test fun laSerieMigreeADesRappelsApres() {
        val ancienne = poubelles.copy(fait = true)
        val migree = ancienne.sansFaitAncien(maintenant)
        assertNotNull(migree)
        val r = reglagesSansAndroid()
        assertTrue(rappelsEntre(listOf(migree), maintenant, maintenant.plusDays(60), r).size >= 8)
    }
}
