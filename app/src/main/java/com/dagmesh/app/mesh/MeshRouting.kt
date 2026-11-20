package com.dagmesh.app.mesh

import java.util.PriorityQueue

/*
 * Kalabalık ağ kuralları. Android'e bağlı değildir; app/src/test altındaki köy simülasyonu
 * (200 telefon) bu kuralları her derlemede sınar.
 */

/**
 * Yol hafızası: "X'e hangi komşu üzerinden, kaç atlamada ulaşılır?"
 * Sadece imzası doğrulanmış yayınlardan (duyuru, SOS, yoklama) öğrenilir; sahte paketle yol zehirlenmez.
 * Kişiye özel paketler en iyi bir-iki yoldan gider; yol bilinmiyorsa eskisi gibi herkese yayılır.
 */
class RouteTable(private val now: () -> Long = System::currentTimeMillis) {
    data class Route(val linkId: String, val hops: Int, val at: Long)

    private val routes = HashMap<String, MutableList<Route>>()

    @Synchronized
    fun learn(dest: String, linkId: String, hops: Int) {
        val t = now()
        val list = routes.getOrPut(dest) { ArrayList(4) }
        val i = list.indexOfFirst { it.linkId == linkId }
        if (i >= 0) {
            val old = list[i]
            // Daha kısa ya da aynı uzunlukta yol, ya da eskimiş kaydın tazelenmesi
            if (hops <= old.hops || t - old.at > REFRESH_MS) list[i] = Route(linkId, hops, t)
        } else {
            list.add(Route(linkId, hops, t))
            if (list.size > MAX_PER_DEST) {
                list.sortWith(compareBy<Route> { it.hops }.thenByDescending { it.at })
                list.removeAt(list.lastIndex)
            }
        }
    }

    /**
     * En iyi yol ve [backup] ise (neredeyse aynı iyilikteki) yedek yol; [exclude] bağlantısı hariç.
     * Yedek yolu sadece mesajı ilk gönderen kullanır: mesaj iki ayrı yola çıkar ama ağda çoğalmaz.
     */
    @Synchronized
    fun next(dest: String, maxAgeMs: Long, exclude: String? = null, backup: Boolean = true): List<Route> {
        val t = now()
        val fresh = routes[dest]?.filter { it.linkId != exclude && t - it.at <= maxAgeMs } ?: return emptyList()
        if (fresh.isEmpty()) return emptyList()
        val sorted = fresh.sortedWith(compareBy<Route> { it.hops }.thenByDescending { it.at })
        val best = sorted[0]
        val second = if (backup) sorted.getOrNull(1)?.takeIf { it.hops <= best.hops + 1 } else null
        return listOfNotNull(best, second)
    }

    @Synchronized
    fun dropLink(linkId: String) {
        val it = routes.values.iterator()
        routes.values.forEach { list -> list.removeAll { r -> r.linkId == linkId } }
        while (it.hasNext()) if (it.next().isEmpty()) it.remove()
    }

    @Synchronized
    fun prune(maxAgeMs: Long) {
        val t = now()
        val it = routes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.removeAll { t - it.at > maxAgeMs }
            if (e.value.isEmpty()) it.remove()
        }
    }

    @Synchronized
    fun size(): Int = routes.size

    companion object {
        private const val MAX_PER_DEST = 4
        private const val REFRESH_MS = 20_000L
    }
}

/** Komşu sınırı: kalabalıkta herkese bağlanmaya çalışıp bağlantıları koparmamak için. */
object LinkPolicy {
    /** Bu sayıya kadar gördüğü herkese bağlanır. */
    const val SOFT = 5
    /**
     * Ağ oturduktan sonra bu sayıya kadar sadece başka yoldan ulaşılamayan telefonlara bağlanır
     * (ağ ikiye bölünmesin). Bluetooth aynı anda en fazla 7 cihazla sağlıklı çalışır.
     */
    const val HARD = 7
    /** Kimin kime ulaştığı ilk duyurularla öğrenilir; o zamana kadar sadece SOFT. */
    const val SETTLE_MS = 60_000L

    fun allows(currentLinks: Int, reachableOtherwise: Boolean, settled: Boolean = true): Boolean = when {
        currentLinks < SOFT -> true
        settled && currentLinks < HARD -> !reachableOtherwise
        else -> false
    }
}

/** Duyuru sıklığı ağ büyüdükçe seyrekleşir; kişi listesi ve köprü bilgisinin ömrü de buna göre uzar. */
object AnnouncePolicy {
    const val MIN_MS = 30_000L
    /** Eski sürümlerin 100 sn'lik zaman aşımıyla uyumlu kalmak için üst sınır. */
    const val MAX_MS = 80_000L

    fun intervalMs(knownPeers: Int): Long =
        (MIN_MS + maxOf(0, knownPeers - 20) * 400L).coerceIn(MIN_MS, MAX_MS)

    /** Üç duyuru kaçsa bile kişi listesinden düşmez. */
    fun timeoutMs(knownPeers: Int): Long = maxOf(100_000L, intervalMs(knownPeers) * 3 + 10_000L)
}

/** Gönderim önceliği: hat dolunca SOS her zaman önce gider. Küçük sayı = önce. */
object SendPriority {
    const val URGENT = 0
    const val HIGH = 1
    const val NORMAL = 2
    const val BULK = 3

    fun of(type: String): Int = when (type) {
        Packet.TYPE_SOS, Packet.TYPE_SOS_ACK, Packet.TYPE_CALL -> URGENT
        Packet.TYPE_MSG, Packet.TYPE_ACK, Packet.TYPE_FAM, Packet.TYPE_STATUS,
        Packet.TYPE_ANNOUNCE, Packet.TYPE_NET, Packet.TYPE_NET_R -> HIGH
        Packet.TYPE_CHUNK, Packet.TYPE_NACK -> BULK
        else -> NORMAL
    }
}

/**
 * Öncelikli gönderim kuyruğu. Aynı anda en fazla [maxInFlight] gönderim yürür; biri bitince
 * kuyruktaki en öncelikli iş başlar. Kuyruk çok şişerse önce en önemsiz işler atılır (SOS asla).
 */
class SendQueue(private val maxInFlight: Int = 6, private val maxQueued: Int = 3_000) {
    private class Job(val prio: Int, val seq: Long, val run: (done: () -> Unit) -> Unit)

    private val queue = PriorityQueue<Job>(compareBy<Job> { it.prio }.thenBy { it.seq })
    private var inFlight = 0
    private var seq = 0L
    var dropped = 0L
        private set

    fun submit(prio: Int, run: (done: () -> Unit) -> Unit) {
        val start = ArrayList<Job>(2)
        synchronized(this) {
            queue.add(Job(prio, seq++, run))
            if (queue.size > maxQueued) trim()
            while (inFlight < maxInFlight && queue.isNotEmpty()) { inFlight++; start += queue.poll()!! }
        }
        start.forEach { launch(it) }
    }

    private fun trim() {
        // En düşük öncelikli ve en yeni işleri at; acil ve yüksek öncelikliler kalır.
        val victims = queue.filter { it.prio >= SendPriority.NORMAL }
            .sortedWith(compareByDescending<Job> { it.prio }.thenByDescending { it.seq })
            .take(queue.size - maxQueued)
        victims.forEach { queue.remove(it) }
        dropped += victims.size
    }

    // Gönderim hemen biterse (ör. bağlantı yok) iç içe çağrı yığını büyümesin diye aynı iş parçacığında sırayla çalıştır.
    private val draining = ThreadLocal<ArrayDeque<Job>?>()

    private fun launch(job: Job) {
        draining.get()?.let { it.addLast(job); return }
        val work = ArrayDeque<Job>()
        draining.set(work)
        try {
            work.addLast(job)
            while (work.isNotEmpty()) runOne(work.removeFirst())
        } finally {
            draining.set(null)
        }
    }

    private fun runOne(job: Job) {
        var released = false
        val done = {
            var next: Job? = null
            synchronized(this) {
                if (!released) {
                    released = true
                    inFlight--
                    if (inFlight < maxInFlight && queue.isNotEmpty()) { inFlight++; next = queue.poll() }
                }
            }
            next?.let { launch(it) }
            Unit
        }
        try { job.run(done) } catch (e: Exception) { done() }
    }

    @Synchronized
    fun pending(): Int = queue.size
}
