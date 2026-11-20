package com.dagmesh.app.enkaz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Enkaz düdüğü tanıma testleri. Bu testler geçmeden sürüm çıkmaz (CI).
 * Gürültü düzeyleri, düdüğün 1500–3000 Hz bandındaki güce göre ayarlanır.
 */
class EnkazDspTest {
    private val fs = EnkazSignal.FS

    private fun white(n: Int, sigma: Double, rnd: Random) = FloatArray(n) { (rnd.nextGaussian() * sigma).toFloat() }

    /** Beyaz gürültüde düdük bandındaki güç: σ² × (1500 / 8000). */
    private fun ampForSnr(sigma: Double, snrDb: Double): Float =
        sqrt(2 * sigma * sigma * (1500.0 / 8000.0) * Math.pow(10.0, snrDb / 10)).toFloat()

    private fun add(dst: FloatArray, src: FloatArray, at: Int) {
        for (i in src.indices) if (at + i < dst.size) dst[at + i] += src[i]
    }

    /** Basit enkaz kanalı: birkaç gecikmeli yol + sönen yankı. */
    private fun rubble(x: FloatArray, rnd: Random): FloatArray {
        val y = x.copyOf()
        repeat(5) {
            val d = 20 + rnd.nextInt(600)
            val g = (rnd.nextGaussian() * 0.5).toFloat()
            for (i in x.indices) if (i + d < y.size) y[i + d] += x[i] * g
        }
        val m = (0.3 * fs).toInt()
        val tail = FloatArray(m) { (rnd.nextGaussian() * exp(-6.9 * it / m)).toFloat() }
        var e = 0.0; for (v in tail) e += v * v
        val g = (0.3 / sqrt(e)).toFloat()
        val out = y.copyOf()
        // seyrek konvolüsyon (her 7. kuyruk örneği) yeterli ve hızlı
        var k = 1
        while (k < m) {
            val t = tail[k] * g * 2.6f
            for (i in x.indices) if (i + k < out.size) out[i + k] += x[i] * t
            k += 7
        }
        return out
    }

    private fun run(det: PatternDetector, x: FloatArray): List<PatternDetector.Detection> {
        val all = ArrayList<PatternDetector.Detection>()
        var i = 0
        while (i < x.size) {
            val n = minOf(1024, x.size - i)
            all += det.feed(x.copyOfRange(i, i + n))
            i += n
        }
        return all
    }

    private fun near(d: List<PatternDetector.Detection>, start: Int) = d.any { abs(it.start - start) < fs / 20 }

    @Test
    fun temizDuduk_bulunur() {
        val rnd = Random(1)
        val x = white(12 * fs, 0.01, rnd)
        add(x, EnkazSignal.pattern(fs, true, 0.3f), 4 * fs)
        val d = run(PatternDetector(true), x)
        assertTrue("bulunamadı: $d", near(d, 4 * fs))
        assertEquals("tek dizi bir kez sayılmalı: $d", 1, d.size)
    }

    @Test
    fun kisikDuduk_gurultuAltinda_bulunur() {
        // Düdük bandında gürültüden 12 dB kısık, yankılı enkaz kanalından geçmiş.
        var hits = 0
        for (seed in 1..6) {
            val rnd = Random(seed.toLong())
            val sigma = 0.05
            val x = white(14 * fs, sigma, rnd)
            add(x, rubble(EnkazSignal.pattern(fs, true, ampForSnr(sigma, -12.0)), rnd), 5 * fs)
            if (near(run(PatternDetector(true), x), 5 * fs)) hits++
        }
        assertTrue("6 denemede $hits yakalandı", hits >= 5)
    }

    @Test
    fun beyazGurultu_yanlisAlarmYok() {
        val rnd = Random(7)
        val x = white(180 * fs, 0.05, rnd)
        val d = run(PatternDetector(true), x)
        assertTrue("yanlış alarm: $d", d.isEmpty())
    }

    @Test
    fun vuruslar_yanlisAlarmYok() {
        val rnd = Random(8)
        val x = white(150 * fs, 0.003, rnd)
        var t = fs
        while (t < x.size - 1000) {
            for (i in 0 until 800) x[t + i] += (rnd.nextGaussian() * exp(-i / 80.0) * 0.6).toFloat()
            t += (0.3 * fs + rnd.nextInt(fs)).toInt()
        }
        val d = run(PatternDetector(true), x)
        assertTrue("vuruşlar düdük sanıldı: $d", d.isEmpty())
    }

    @Test
    fun arabaAlarmi_saniyelikKayanTon_yanlisAlarmYok() {
        val rnd = Random(9)
        val n = 120 * fs
        val x = white(n, 0.01, rnd)
        var ph = 0.0
        for (i in 0 until n) {
            val p = (i.toDouble() / fs) % 1.0
            val f = if (p < 0.5) 1500 + 3000 * p else 3000 - 3000 * (p - 0.5)
            ph += 2 * PI * f / fs
            x[i] += (0.3 * sin(ph) + 0.1 * sin(2 * ph)).toFloat()
        }
        val d = run(PatternDetector(true), x)
        assertTrue("alarm düdük sanıldı: $d", d.isEmpty())
    }

    @Test
    fun siren_yanlisAlarmYok() {
        val rnd = Random(10)
        val n = 120 * fs
        val x = white(n, 0.01, rnd)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / fs
            val f = 1100 + 500 * sin(2 * PI * t / 4.0)
            ph += 2 * PI * f / fs
            x[i] += (0.3 * sin(ph) + 0.15 * sin(2 * ph) + 0.08 * sin(3 * ph)).toFloat()
        }
        val d = run(PatternDetector(true), x)
        assertTrue("siren düdük sanıldı: $d", d.isEmpty())
    }

    @Test
    fun soruVeDuduk_birbirineKarismaz() {
        val rnd = Random(11)
        val x = white(14 * fs, 0.01, rnd)
        add(x, EnkazSignal.pattern(fs, false, 0.3f), 5 * fs)
        val up = run(PatternDetector(true), x)
        assertTrue("iniş sesi düdük sanıldı: $up", up.isEmpty())
        val down = run(PatternDetector(false), x)
        assertTrue("soru bulunamadı: $down", near(down, 5 * fs))
    }

    @Test
    fun guc_yaklastikcaArtar() {
        fun strength(a: Float): Double {
            val rnd = Random(12)
            val x = white(14 * fs, 0.05, rnd)
            add(x, EnkazSignal.pattern(fs, true, a), 5 * fs)
            return run(PatternDetector(true), x).first { abs(it.start - 5 * fs) < fs / 20 }.strengthDb
        }
        val weak = strength(ampForSnr(0.05, -6.0))
        val strong = strength(ampForSnr(0.05, 14.0))
        assertEquals("20 dB yaklaşınca gösterge de ~20 dB artmalı", 20.0, strong - weak, 4.0)
    }

    @Test
    fun titresim_ritmiDuyulur_gurultudeDuyulmaz() {
        val rnd = Random(13)
        val n = 10 * fs
        val start = 2L * fs
        val x = white(n, 0.002, rnd)
        val pulse = EnkazSignal.VIB_ON_MS + EnkazSignal.VIB_OFF_MS
        for (p in 0 until EnkazSignal.VIB_PULSES) {
            val a = (start + (EnkazSignal.VIB_START_MS - 60 + p * pulse) * fs / 1000).toInt()
            for (i in 0 until (EnkazSignal.VIB_ON_MS * fs / 1000).toInt()) x[a + i] += (0.02 * sin(2 * PI * 180 * i / fs)).toFloat()
        }
        val probe = VibrationProbe()
        probe.feed(x)
        assertTrue(probe.ready(start))
        assertTrue("titreşim duyulmadı", probe.heard(start))

        val quiet = VibrationProbe()
        quiet.feed(white(n, 0.002, Random(14)))
        assertFalse("gürültü titreşim sanıldı", quiet.heard(start))
    }

    @Test
    fun seslenme_tetikler_sabitGurultu_tetiklemez() {
        val rnd = Random(15)
        val trig = VoiceTrigger()
        val noise = white(20 * fs, 0.003, rnd)
        var hits = 0
        var i = 0
        while (i < noise.size) { if (trig.feed(noise.copyOfRange(i, minOf(noise.size, i + 1024)))) hits++; i += 1024 }
        assertEquals("sabit gürültüde tetiklendi", 0, hits)
        // "Sesimi duyan var mı?" benzeri: 1,5 sn konuşma bandı sesi
        val voice = FloatArray(fs * 2)
        for (j in 0 until (1.5 * fs).toInt()) {
            val t = j.toDouble() / fs
            voice[j] = (0.05 * sin(2 * PI * 180 * t) * (1 + sin(2 * PI * 4 * t)) + 0.03 * sin(2 * PI * 900 * t) + rnd.nextGaussian() * 0.003).toFloat()
        }
        var got = false
        i = 0
        while (i < voice.size) { if (trig.feed(voice.copyOfRange(i, minOf(voice.size, i + 1024)))) got = true; i += 1024 }
        assertTrue("seslenme duyulmadı", got)
    }
}
