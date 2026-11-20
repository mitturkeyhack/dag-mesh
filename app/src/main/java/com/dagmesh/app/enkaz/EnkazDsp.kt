package com.dagmesh.app.enkaz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Enkaz düdüğü: sinyal tanımı ve tanıma. Bu dosya Android'e bağlı değildir;
 * app/src/test altındaki birim testleri her derlemede bunu gerçekçi gürültüyle sınar.
 *
 * Sinyal: 1500→3000 Hz kayan 0,8 sn'lik düdük, 1 sn arayla 4 kez (toplam 3,8 sn).
 * Arayan telefonun "Sesimi duyan var mı?" sorusu aynı yapıda ama 3000→1500 Hz iner;
 * iki yön birbirine karışmaz.
 *
 * Tanıma: eşleşen süzgeç (şablonla ilişki) + 4 düdüğün bilinen aralıkla birleştirilmesi.
 * Vuruş gibi ani sesler önce bastırılır; düz/yavaş kayan tonlar (siren, alarm) tepe
 * keskinliği kontrolüyle elenir.
 */
object EnkazSignal {
    const val FS = 16_000
    const val CHIRP_SEC = 0.8
    const val F_LOW = 1500.0
    const val F_HIGH = 3000.0
    const val PERIOD_SEC = 1.0
    const val COUNT = 4
    const val PATTERN_SEC = (COUNT - 1) * PERIOD_SEC + CHIRP_SEC

    // Düdüklerden sonra titreşim vuruşu: 4,0 sn'de başlar, 3 kez 250 ms açık / 250 ms kapalı.
    const val VIB_START_MS = 4_000L
    const val VIB_ON_MS = 250L
    const val VIB_OFF_MS = 250L
    const val VIB_PULSES = 3

    fun chirp(fs: Int, up: Boolean): FloatArray {
        val n = (CHIRP_SEC * fs).roundToInt()
        val f0 = if (up) F_LOW else F_HIGH
        val f1 = if (up) F_HIGH else F_LOW
        val k = (f1 - f0) / CHIRP_SEC
        val taper = 0.05 * (n - 1)
        return FloatArray(n) { i ->
            val t = i.toDouble() / fs
            val w = when {
                i < taper -> 0.5 * (1 - cos(PI * i / taper))
                i > n - 1 - taper -> 0.5 * (1 - cos(PI * (n - 1 - i) / taper))
                else -> 1.0
            }
            (sin(2 * PI * (f0 * t + 0.5 * k * t * t)) * w).toFloat()
        }
    }

    fun pattern(fs: Int, up: Boolean, amplitude: Float = 1f): FloatArray {
        val c = chirp(fs, up)
        val period = (PERIOD_SEC * fs).roundToInt()
        val out = FloatArray((COUNT - 1) * period + c.size)
        for (k in 0 until COUNT) {
            val off = k * period
            for (i in c.indices) out[off + i] += c[i] * amplitude
        }
        return out
    }

    fun toPcm16(x: FloatArray): ShortArray =
        ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
}

/** İkinci dereceden bölümler halinde IIR süzgeç (Butterworth katsayıları scipy ile 16 kHz için hesaplandı). */
class SosFilter(private val sos: Array<DoubleArray>) {
    private val z1 = DoubleArray(sos.size)
    private val z2 = DoubleArray(sos.size)

    fun process(x: Float): Float {
        var v = x.toDouble()
        for (s in sos.indices) {
            val c = sos[s]
            val y = c[0] * v + z1[s]
            z1[s] = c[1] * v - c[4] * y + z2[s]
            z2[s] = c[2] * v - c[5] * y
            v = y
        }
        return v.toFloat()
    }

    companion object {
        /** 1200–3400 Hz, 8. derece: düdük bandı. */
        val BAND = arrayOf(
            doubleArrayOf(0.01397742559072118, 0.02795485118144236, 0.01397742559072118, 1.0, -0.6694758432657585, 0.346768024049006),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.2140482517789697, 0.5038723073893046),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -0.4358272265218758, 0.6666512743061024),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.613487209086611, 0.8238084591522197),
        )

        /** 300–3400 Hz: insan sesi ve vuruş. */
        val VOICE = arrayOf(
            doubleArrayOf(0.196549949108559, 0.393099898217118, 0.196549949108559, 1.0, -0.36585212957866553, 0.23798771501112476),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.8356268799614734, 0.850277363071116),
        )

        /** 100–400 Hz: titreşim motoru. */
        val LOW = arrayOf(
            doubleArrayOf(0.003199828966843965, 0.00639965793368793, 0.003199828966843965, 1.0, -1.8637387928660014, 0.8816996711567416),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.958162061356789, 0.9601137467607542),
        )
    }
}

/** Yerinde, 2'nin kuvveti boyda karmaşık FFT. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2)
    private val sinT = DoubleArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n > 1 && (n and (n - 1)) == 0)
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until n / 2) {
            cosT[i] = cos(2 * PI * i / n)
            sinT[i] = -sin(2 * PI * i / n)
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) -sinT[k] else sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

private fun median(a: FloatArray, n: Int): Double {
    if (n <= 0) return 0.0
    val c = a.copyOf(n)
    c.sort()
    return if (n % 2 == 1) c[n / 2].toDouble() else (c[n / 2 - 1] + c[n / 2]) / 2.0
}

/**
 * Akış halinde düdük dizisi tanıyıcı. 16 kHz örnek verilir; bulunan her dizi için
 * başlangıç örnek numarası ve gücü (dB, gürültü tabanına göre) döner.
 *
 * up=true: enkazdaki telefonun düdüğü; up=false: arayanın "Sesimi duyan var mı?" sorusu.
 */
class PatternDetector(
    up: Boolean,
    private val threshold: Double = 6.0,
    private val sharpMin: Double = 2.5,
) {
    /** revises=true: hemen önce bildirilen dizinin daha güçlü ölçümü (yeni olay değil). */
    data class Detection(val start: Long, val strengthDb: Double, val sharp: Double, val revises: Boolean = false)

    private val fs = EnkazSignal.FS
    private val tpl = EnkazSignal.chirp(fs, up)
    private val l = tpl.size
    private val n = 32_768
    private val hop = n - l + 1
    private val period = (EnkazSignal.PERIOD_SEC * fs).roundToInt()
    private val count = EnkazSignal.COUNT
    private val tol = (0.002 * fs).roundToInt()
    private val shw = (0.20 * fs).roundToInt()
    private val exc = (0.006 * fs).roundToInt()
    private val blk = fs / 2
    private val hist = 40

    private val band = SosFilter(SosFilter.BAND)
    private val fft = Fft(n)
    private val hRe = DoubleArray(n)
    private val hIm = DoubleArray(n)

    // Ani ses bastırıcı (4 ms çerçeveler)
    private val frameLen = 64
    private val frame = FloatArray(frameLen)
    private var frameFill = 0
    private val energies = FloatArray(62)
    private var energyCount = 0
    private var energyPos = 0
    private var hold = 0

    private val win = DoubleArray(n)
    private val wIm = DoubleArray(n)
    private var winFill = 0
    private var segStart = 0L

    private val ringSize = 1 shl 18
    private val mask = ringSize - 1
    private val amp = FloatArray(ringSize)
    private val z = FloatArray(ringSize)
    private var ampEnd = 0L
    private var zEnd = 0L
    private val blockBuf = FloatArray(blk)
    private var blockFill = 0
    private val medians = ArrayDeque<Double>()

    private var dNext = tol.toLong()
    private var bestT = -1L
    private var bestD = 0.0
    private var clusterEnd = 0L
    private val out = ArrayList<Detection>()

    /** Son yarım saniyedeki en yüksek z (gösterge çubukları için, dB). */
    @Volatile var levelDb: Double = 0.0
        private set

    /** Şimdiye kadar verilen örnek sayısı. */
    var samples: Long = 0
        private set

    init {
        val tr = DoubleArray(n)
        val ti = DoubleArray(n)
        var energy = 0.0
        for (i in 0 until l) { tr[i] = tpl[i].toDouble(); energy += tpl[i] * tpl[i] }
        fft.transform(tr, ti, false)
        val norm = 1.0 / sqrt(energy)
        for (f in 0 until n) {
            val h = when {
                f == 0 || f == n / 2 -> 1.0
                f < n / 2 -> 2.0
                else -> 0.0
            }
            // konj(T) * H / |t|
            hRe[f] = tr[f] * h * norm
            hIm[f] = -ti[f] * h * norm
        }
    }

    fun feed(x: FloatArray, len: Int = x.size): List<Detection> {
        out.clear()
        for (i in 0 until len) {
            frame[frameFill++] = band.process(x[i])
            if (frameFill == frameLen) {
                pushFrame()
                frameFill = 0
            }
        }
        samples += len
        return out.toList()
    }

    private fun pushFrame() {
        var e = 0.0
        for (v in frame) e += v * v
        e /= frameLen
        val ref = if (energyCount >= 8) median(energies, energyCount) else Double.MAX_VALUE
        if (e > ref * 16.0) hold = 4
        val blanked = hold > 0
        if (hold > 0) hold--
        energies[energyPos] = e.toFloat()
        energyPos = (energyPos + 1) % energies.size
        if (energyCount < energies.size) energyCount++
        for (v in frame) {
            win[winFill++] = if (blanked) 0.0 else v.toDouble()
            if (winFill == n) processWindow()
        }
    }

    private fun processWindow() {
        java.util.Arrays.fill(wIm, 0.0)
        val re = win.copyOf()
        fft.transform(re, wIm, false)
        for (f in 0 until n) {
            val a = re[f]; val b = wIm[f]
            re[f] = a * hRe[f] - b * hIm[f]
            wIm[f] = a * hIm[f] + b * hRe[f]
        }
        fft.transform(re, wIm, true)
        for (m in 0 until hop) pushAmp(segStart + m, hypot(re[m], wIm[m]).toFloat())
        System.arraycopy(win, hop, win, 0, l - 1)
        winFill = l - 1
        segStart += hop
    }

    private fun pushAmp(idx: Long, a: Float) {
        amp[(idx and mask.toLong()).toInt()] = a
        ampEnd = idx + 1
        blockBuf[blockFill++] = a
        if (blockFill == blk) {
            medians.addLast(median(blockBuf, blk))
            while (medians.size > hist + 1) medians.removeFirst()
            val sorted = medians.sorted()
            val scale = (sorted[(0.25 * (sorted.size - 1)).toInt()] / 1.1774).coerceAtLeast(1e-12)
            var maxZ = 0.0
            val from = ampEnd - blk
            for (j in from until ampEnd) {
                val zz = amp[(j and mask.toLong()).toInt()] / scale
                z[(j and mask.toLong()).toInt()] = zz.toFloat()
                if (zz > maxZ) maxZ = zz
            }
            zEnd = ampEnd
            levelDb = if (maxZ > 0) 20 * log10(maxZ) else 0.0
            blockFill = 0
            if (medians.size >= 4) combine()
            else dNext = zEnd
        }
    }

    private fun zAt(i: Long) = z[(i and mask.toLong()).toInt()]
    private fun ampAt(i: Long) = amp[(i and mask.toLong()).toInt()]

    private fun combine() {
        val span = (count - 1).toLong() * period + tol
        val comp = FloatArray(count)
        while (dNext + span < zEnd) {
            var s = 0.0
            var strong = 0
            for (k in 0 until count) {
                val c = dNext + k.toLong() * period
                var m = 0f
                var j = c - tol
                while (j <= c + tol) { val v = zAt(j); if (v > m) m = v; j++ }
                comp[k] = m
                if (m >= COMPONENT_MIN) strong++
                s += m.toDouble() * m
            }
            // Tek bir güçlü tepe (siren, alarm) dizi sayılmasın: en az 3 düdük ayrı ayrı duyulmalı.
            val d = if (strong >= count - 1) sqrt(s / count) else 0.0
            if (d >= threshold) {
                if (bestT < 0 || d > bestD) { bestT = dNext; bestD = d }
                clusterEnd = dNext + fs / 2
            } else if (bestT >= 0 && dNext > clusterEnd) {
                finish()
            }
            dNext++
        }
    }

    private fun finish() {
        val t = bestT
        val d = bestD
        bestT = -1
        bestD = 0.0
        if (ampEnd - t > ringSize - 4L * fs) return
        val buf = FloatArray(2 * shw)
        var sharpSum = 0.0
        var sharpCount = 0
        for (k in 0 until count) {
            val c = t + k.toLong() * period
            var q = c
            var best = -1f
            for (j in c - tol..c + tol) { val v = ampAt(j); if (v > best) { best = v; q = j } }
            var cnt = 0
            for (j in q - shw until q + shw) {
                if (j < 0 || (j > q - exc && j < q + exc) || j >= ampEnd) continue
                buf[cnt++] = ampAt(j)
            }
            val med = median(buf, cnt)
            val sh = if (med > 0) best / med else 0.0
            sharpSum += sh
            if (sh >= 2.0) sharpCount++
        }
        val sharp = sharpSum / count
        if (sharp < sharpMin || sharpCount < count - 1) return
        offer(Detection(t, 20 * log10(d), sharp))
    }

    // Bir dizi, bir saniye kaymış haliyle de (4 düdüğün 3'ü) eşleşir. Hemen bildir; aynı pencerede
    // daha güçlüsü gelirse onu "düzeltme" olarak bildir, zayıfını at.
    private val suppressWin = (count - 1).toLong() * period + fs / 2
    private var lastEmitted: Detection? = null

    private fun offer(c: Detection) {
        val p = lastEmitted
        if (p != null && kotlin.math.abs(c.start - p.start) <= suppressWin) {
            if (c.strengthDb > p.strengthDb) {
                val r = c.copy(revises = true)
                out.add(r); lastEmitted = r
            }
        } else {
            out.add(c); lastEmitted = c
        }
    }

    companion object {
        const val COMPONENT_MIN = 3.0f
    }
}

/** Titreşim motorunun "tık-tık-tık" ritmini, bulunan düdük dizisinden sonra arar. */
class VibrationProbe {
    private val fs = EnkazSignal.FS
    private val low = SosFilter(SosFilter.LOW)
    private val frameLen = 128 // 8 ms
    private val ring = FloatArray(2048) // ~16 sn
    private var frames = 0L
    private var acc = 0.0
    private var fill = 0

    fun feed(x: FloatArray, len: Int = x.size) {
        for (i in 0 until len) {
            val v = low.process(x[i])
            acc += v * v
            if (++fill == frameLen) {
                ring[(frames % ring.size).toInt()] = (acc / frameLen).toFloat()
                frames++
                acc = 0.0
                fill = 0
            }
        }
    }

    /** Dizinin başlangıç örneğine göre titreşim verisi tamamlandı mı? */
    fun ready(patternStart: Long): Boolean =
        frames * frameLen >= patternStart + (EnkazSignal.VIB_START_MS + 1_600) * fs / 1000

    /** Titreşim ritmi duyuldu mu? En az 2 vuruşta açık/kapalı farkı 8 dB üstünde olmalı. */
    fun heard(patternStart: Long): Boolean {
        val msToFrame = { ms: Long -> (patternStart + ms * fs / 1000) / frameLen }
        if (frames - msToFrame(0) > ring.size - 10) return false
        fun mean(fromMs: Long, toMs: Long): Double {
            val a = msToFrame(fromMs); val b = msToFrame(toMs)
            var s = 0.0; var c = 0
            for (f in a until b) { if (f in 0 until frames) { s += ring[(f % ring.size).toInt()]; c++ } }
            return if (c > 0) s / c else 0.0
        }
        val pulse = EnkazSignal.VIB_ON_MS + EnkazSignal.VIB_OFF_MS
        var offset = -200L
        while (offset <= 50L) {
            var ok = 0
            for (p in 0 until EnkazSignal.VIB_PULSES) {
                val on0 = EnkazSignal.VIB_START_MS + offset + p * pulse
                val on = mean(on0 + 60, on0 + 190)
                val before = mean(on0 - 190, on0 - 60)
                val after = mean(on0 + 310, on0 + 440)
                val off = if (p == EnkazSignal.VIB_PULSES - 1) before else (before + after) / 2
                if (off > 0 && on / off > 6.3) ok++
            }
            if (ok >= 2) return true
            offset += 25
        }
        return false
    }
}

/**
 * Enkazdaki telefonun "biri sesleniyor ya da vuruyor" algısı: 300–3400 Hz bandında
 * son 30 sn'nin sessiz tabanından 12 dB yüksek kısa sesler.
 */
class VoiceTrigger {
    private val f = SosFilter(SosFilter.VOICE)
    private val frameLen = 512 // 32 ms
    private val ring = FloatArray(940)
    private var count = 0
    private var pos = 0
    private var acc = 0.0
    private var fill = 0
    private var floor = 0.0
    private var sinceFloor = 0
    private val recent = BooleanArray(8)
    private var rPos = 0

    /** true: bu parçada dışarıdan anlamlı bir ses geldi. */
    fun feed(x: FloatArray, len: Int = x.size): Boolean {
        var hit = false
        for (i in 0 until len) {
            val v = f.process(x[i])
            acc += v * v
            if (++fill == frameLen) {
                val e = acc / frameLen
                acc = 0.0; fill = 0
                ring[pos] = e.toFloat(); pos = (pos + 1) % ring.size
                if (count < ring.size) count++
                if (++sinceFloor >= 32 || count == 60) {
                    sinceFloor = 0
                    val c = ring.copyOf(count); c.sort()
                    floor = c[(0.2 * (count - 1)).toInt()].toDouble().coerceAtLeast(1e-10)
                }
                recent[rPos] = count >= 60 && e > floor * 16.0
                rPos = (rPos + 1) % recent.size
                if (recent.count { it } >= 3) {
                    hit = true
                    java.util.Arrays.fill(recent, false)
                }
            }
        }
        return hit
    }
}
