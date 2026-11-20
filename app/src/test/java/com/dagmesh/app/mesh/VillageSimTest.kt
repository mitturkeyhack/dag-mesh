package com.dagmesh.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * Köy simülasyonu: 200 telefon 800×800 m'lik bir köye dağılır, her telefon ~70 m'deki
 * telefonları duyar. Bağlantı sınırı, yol hafızası ve duyuru sıklığı burada birlikte sınanır.
 */
class VillageSimTest {

    private class Village(val n: Int, size: Double, range: Double, seed: Long) {
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val inRange: List<List<Int>>

        init {
            val rnd = Random(seed)
            for (i in 0 until n) { x[i] = rnd.nextDouble() * size; y[i] = rnd.nextDouble() * size }
            inRange = List(n) { i -> (0 until n).filter { j -> j != i && hypot(x[i] - x[j], y[i] - y[j]) <= range } }
        }
    }

    private fun components(n: Int, adj: List<Collection<Int>>): IntArray {
        val comp = IntArray(n) { -1 }
        var c = 0
        for (s in 0 until n) {
            if (comp[s] >= 0) continue
            val stack = ArrayDeque<Int>(); stack.add(s); comp[s] = c
            while (stack.isNotEmpty()) {
                val u = stack.removeLast()
                for (v in adj[u]) if (comp[v] < 0) { comp[v] = c; stack.add(v) }
            }
            c++
        }
        return comp
    }

    /** Telefonlar sırayla birbirini bulur; ikisi de LinkPolicy'ye göre izin verirse bağlanır. */
    private fun connectWithCap(v: Village, seed: Long, capped: Boolean = true): List<MutableSet<Int>> {
        val rnd = Random(seed)
        val links = List(v.n) { HashSet<Int>() }
        repeat(8) { round ->
            val settled = round >= 2 // ilk duyurular yayılana kadar sadece SOFT
            val comp = components(v.n, links) // yaklaşık: duyurular yayıldıktan sonra kim kime ulaşabiliyor
            val pairs = ArrayList<Pair<Int, Int>>()
            for (i in 0 until v.n) for (j in v.inRange[i]) if (i < j && j !in links[i]) pairs += i to j
            pairs.shuffle(rnd)
            for ((i, j) in pairs) {
                val reach = comp[i] == comp[j]
                val ok = !capped || (LinkPolicy.allows(links[i].size, reach, settled) && LinkPolicy.allows(links[j].size, reach, settled))
                if (ok) { links[i] += j; links[j] += i }
            }
        }
        return links
    }

    @Test
    fun baglantiSiniri_agiBolmez_veSinirAsilmaz() {
        // Seyrek köy (800 m) ve kalabalık toplanma alanı (200 m: herkes herkesi görüyor)
        for ((size, seed) in listOf(800.0 to 1L, 800.0 to 2L, 800.0 to 3L, 200.0 to 4L, 150.0 to 5L)) {
            val v = Village(200, size, 70.0, seed)
            val base = components(v.n, v.inRange)
            val links = connectWithCap(v, seed)
            val capped = components(v.n, links)
            // Sınırsızken aynı parçada olan her iki telefon sınırla da aynı parçada kalmalı.
            var broken = 0; var total = 0
            for (i in 0 until v.n) for (j in i + 1 until v.n) if (base[i] == base[j]) { total++; if (capped[i] != capped[j]) broken++ }
            assertEquals("seed $seed: sınır yüzünden kopan çift", 0, broken)
            assertTrue(links.all { it.size <= LinkPolicy.HARD })
            assertTrue("seed $seed: anlamlı bir test için yeterince bağlı çift olmalı ($total)", total > 2000)
            // Kalabalıkta duyuru yükü de sınırlı kalmalı
            var maxSends = 0
            val sends = IntArray(v.n)
            for (o in 0 until v.n) { val f = flood(links, o, Packet.ANNOUNCE_TTL); for (i in 0 until v.n) sends[i] += f.sends[i] }
            maxSends = sends.maxOrNull()!!
            val perSec = maxSends / (AnnouncePolicy.intervalMs(v.n - 1) / 1000.0)
            println("Köy ${size.toInt()} m: en yoğun telefon saniyede ${"%.1f".format(perSec)} duyuru, ortalama komşu ${"%.1f".format(links.map { it.size }.average())}")
            assertTrue("seed $seed: duyuru yükü $perSec/sn", perSec <= 15.0)
            // Her telefon herkese ulaşabilmeli (ömür yetmeli)
            for (o in 0 until v.n step 17) {
                val f = flood(links, o, Packet.ANNOUNCE_TTL)
                for (i in 0 until v.n) if (base[i] == base[o]) assertTrue("seed $seed: $o → $i ulaşılamadı", f.hops[i] >= 0)
            }
        }
    }

    /** Bir duyurunun ağa yayılması: her telefon ilk aldığında bir kez, kendisine gönderenler hariç ilerletir. */
    private class Flood(val hops: IntArray, val sends: IntArray, val learned: Map<Int, MutableList<Pair<Int, Int>>>)

    private fun flood(links: List<Set<Int>>, origin: Int, ttl: Int): Flood {
        val n = links.size
        val hops = IntArray(n) { -1 }
        val sends = IntArray(n)
        val learned = HashMap<Int, MutableList<Pair<Int, Int>>>() // düğüm → (komşu, atlama)
        val parent = IntArray(n) { -1 }
        hops[origin] = 0
        var frontier = listOf(origin)
        var h = 0
        while (frontier.isNotEmpty() && h < ttl) {
            val next = ArrayList<Int>()
            for (u in frontier) for (w in links[u]) {
                if (w == parent[u]) continue
                sends[u]++
                learned.getOrPut(w) { ArrayList() } += u to (h + 1)
                if (hops[w] < 0) { hops[w] = h + 1; parent[w] = u; next += w }
            }
            frontier = next
            h++
        }
        return Flood(hops, sends, learned)
    }

    @Test
    fun yolHafizasi_mesajlarUlasir_veAgiYormaz() {
        val v = Village(200, 800.0, 70.0, 7)
        val links = connectWithCap(v, 7)
        var clock = 0L
        val tables = List(v.n) { RouteTable { clock } }
        val ttl = Packet.DEFAULT_TTL
        val totalSends = IntArray(v.n)
        val reach = Array(v.n) { IntArray(v.n) { -1 } }
        for (o in 0 until v.n) {
            val f = flood(links, o, Packet.ANNOUNCE_TTL)
            for (i in 0 until v.n) totalSends[i] += f.sends[i]
            for ((node, list) in f.learned) for ((nb, hh) in list) tables[node].learn("p$o", "l$nb", hh)
            reach[o] = f.hops
        }

        // 1) Duyuru yükü: her telefonun saniyede gönderdiği duyuru paketi
        val interval = AnnouncePolicy.intervalMs(v.n - 1) / 1000.0
        val perSec = totalSends.map { it / interval }
        val maxLoad = perSec.maxOrNull()!!
        val meanLoad = perSec.average()
        assertTrue("en yoğun telefon saniyede $maxLoad duyuru taşıyor", maxLoad <= 15.0)
        assertTrue("ortalama yük $meanLoad", meanLoad <= 6.0)

        // 2) Kişiye özel mesajlar: yol hafızasıyla, en fazla 2 yoldan
        val rnd = Random(11)
        var tried = 0; var delivered = 0; var sendsRouted = 0L; var sendsFlood = 0L; var pathSum = 0L
        while (tried < 400) {
            val a = rnd.nextInt(v.n); val b = rnd.nextInt(v.n)
            if (a == b || reach[b][a] < 0 || reach[b][a] > ttl) continue
            tried++
            pathSum += reach[b][a]
            // Yönlendirme: her telefon ilk aldığında en iyi 1-2 yola iletir, yol yoksa herkese
            val seen = BooleanArray(v.n)
            val queue = ArrayDeque<Triple<Int, Int, Int>>() // düğüm, geldiği komşu, kalan ttl
            seen[a] = true
            queue.add(Triple(a, -1, ttl))
            var ok = false
            while (queue.isNotEmpty()) {
                val (u, from, t) = queue.removeFirst()
                if (u == b) { ok = true; continue }
                if (t <= 0) continue
                val direct = b in links[u]
                val targets = when {
                    direct -> listOf(b)
                    else -> tables[u].next("p$b", Long.MAX_VALUE, exclude = "l$from").map { it.linkId.drop(1).toInt() }
                        .ifEmpty { links[u].filter { it != from } }
                }
                for (w in targets) {
                    sendsRouted++
                    if (!seen[w]) { seen[w] = true; queue.add(Triple(w, u, t - 1)) }
                }
            }
            if (ok) delivered++
            // Karşılaştırma: eski yöntem (herkese yayma) bu mesaj için kaç gönderim yapardı
            sendsFlood += flood(links, a, ttl).sends.sum()
        }
        val ratio = delivered.toDouble() / tried
        assertTrue("ulaşan mesaj oranı %${(ratio * 100).toInt()}", ratio >= 0.995)
        val avgRouted = sendsRouted.toDouble() / tried
        val avgFlood = sendsFlood.toDouble() / tried
        val avgPath = pathSum.toDouble() / tried
        println("Köy simülasyonu: ortalama yol $avgPath atlama, yol hafızasıyla $avgRouted gönderim, eski yöntemle $avgFlood gönderim")
        assertTrue("yol hafızası yükü azaltmalı: $avgRouted / $avgFlood", avgRouted * 10 <= avgFlood)
        assertTrue("gönderim yol uzunluğuyla orantılı kalmalı: $avgRouted / $avgPath", avgRouted <= avgPath * 4 + 4)
    }

    @Test
    fun uzunZincir_12Telefon_ucUcaUlasir() {
        // Dağ yolu: telefonlar tek sıra, her biri sadece komşusunu duyar.
        val n = 12
        val links = List(n) { i -> setOfNotNull(if (i > 0) i - 1 else null, if (i < n - 1) i + 1 else null) }
        val f = flood(links, 0, Packet.ANNOUNCE_TTL)
        assertTrue("duyuru zincirin sonuna ulaşmalı (ttl ${Packet.ANNOUNCE_TTL})", f.hops[n - 1] == n - 1)
        assertTrue("mesaj ömrü zinciri kapsamalı", Packet.DEFAULT_TTL >= n - 1)
    }

    @Test
    fun oncelik_sosHerZamanOnceGider() {
        val q = SendQueue(maxInFlight = 1)
        val order = ArrayList<String>()
        var release: (() -> Unit)? = null
        // İlk iş hattı meşgul tutar, diğerleri kuyrukta bekler.
        q.submit(SendPriority.BULK) { done -> order += "parça-1"; release = done }
        q.submit(SendPriority.BULK) { done -> order += "parça-2"; done() }
        q.submit(SendPriority.NORMAL) { done -> order += "yazıyor"; done() }
        q.submit(SendPriority.URGENT) { done -> order += "SOS"; done() }
        q.submit(SendPriority.HIGH) { done -> order += "mesaj"; done() }
        release!!()
        assertEquals(listOf("parça-1", "SOS", "mesaj", "yazıyor", "parça-2"), order)
    }

    @Test
    fun kuyrukTasarsa_onemsizlerAtilir_sosKalir() {
        val q = SendQueue(maxInFlight = 1, maxQueued = 10)
        var hold: (() -> Unit)? = null
        q.submit(SendPriority.BULK) { done -> hold = done }
        repeat(50) { q.submit(SendPriority.BULK) { it() } }
        var sos = 0
        repeat(3) { q.submit(SendPriority.URGENT) { done -> sos++; done() } }
        assertTrue(q.pending() <= 10)
        hold!!()
        assertEquals(3, sos)
    }

    @Test
    fun yolTablosu_enKisaYoluSecer_eskiyiUnutur() {
        var t = 0L
        val r = RouteTable { t }
        r.learn("x", "a", 5)
        r.learn("x", "b", 3)
        r.learn("x", "c", 4)
        assertEquals(listOf("b", "c"), r.next("x", 60_000).map { it.linkId })
        assertEquals(listOf("c", "a"), r.next("x", 60_000, exclude = "b").map { it.linkId })
        t = 120_000
        r.learn("x", "a", 6)
        assertEquals(listOf("a"), r.next("x", 60_000).map { it.linkId })
        r.dropLink("a")
        assertTrue(r.next("x", 60_000).isEmpty())
    }

    @Test
    fun duyuruSikligi_agBuyudukceSeyreklesir_ustSiniriAsmaz() {
        assertEquals(30_000L, AnnouncePolicy.intervalMs(5))
        assertTrue(AnnouncePolicy.intervalMs(100) > 30_000L)
        assertEquals(AnnouncePolicy.MAX_MS, AnnouncePolicy.intervalMs(500))
        assertTrue("eski sürümlerin 100 sn zaman aşımıyla uyumlu", AnnouncePolicy.MAX_MS + 10_000 <= 100_000)
        assertTrue(AnnouncePolicy.timeoutMs(500) >= AnnouncePolicy.MAX_MS * 3)
    }
}
