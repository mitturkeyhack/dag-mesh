package com.dagmesh.app.call

/**
 * IMA ADPCM ses sıkıştırma: 16 bit sesi 4 bite indirir (4 kat küçük), neredeyse hiç işlemci
 * kullanmaz ve gecikme eklemez. Her kare kendi başlangıç durumunu taşır; böylece bir kare
 * kaybolsa bile sonraki kareler bozulmadan çözülür.
 *
 * Kare biçimi: [tahmin düşük bayt][tahmin yüksek bayt][adım indeksi][4 bitlik örnekler...]
 */
object ImaAdpcm {
    private val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    class State {
        var predictor = 0
        var index = 0
    }

    fun encode(pcm: ShortArray, count: Int, state: State): ByteArray {
        val out = ByteArray(HEADER + (count + 1) / 2)
        out[0] = (state.predictor and 0xFF).toByte()
        out[1] = ((state.predictor shr 8) and 0xFF).toByte()
        out[2] = state.index.toByte()

        var predictor = state.predictor
        var index = state.index
        for (i in 0 until count) {
            var step = stepTable[index]
            var diff = pcm[i].toInt() - predictor
            var nibble = 0
            if (diff < 0) {
                nibble = 8
                diff = -diff
            }
            var delta = step shr 3
            if (diff >= step) {
                nibble = nibble or 4
                diff -= step
                delta += step
            }
            step = step shr 1
            if (diff >= step) {
                nibble = nibble or 2
                diff -= step
                delta += step
            }
            step = step shr 1
            if (diff >= step) {
                nibble = nibble or 1
                delta += step
            }
            predictor = if ((nibble and 8) != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + indexTable[nibble]).coerceIn(0, 88)

            val pos = HEADER + i / 2
            out[pos] = if (i % 2 == 0) {
                (nibble and 0x0F).toByte()
            } else {
                ((out[pos].toInt() and 0x0F) or (nibble shl 4)).toByte()
            }
        }
        state.predictor = predictor
        state.index = index
        return out
    }

    fun decode(data: ByteArray): ShortArray {
        if (data.size <= HEADER) return ShortArray(0)
        var predictor = ((data[0].toInt() and 0xFF) or (data[1].toInt() shl 8)).toShort().toInt()
        var index = (data[2].toInt() and 0xFF).coerceIn(0, 88)
        val count = (data.size - HEADER) * 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val b = data[HEADER + i / 2].toInt() and 0xFF
            val nibble = if (i % 2 == 0) b and 0x0F else b shr 4
            val step = stepTable[index]
            var delta = step shr 3
            if ((nibble and 4) != 0) delta += step
            if ((nibble and 2) != 0) delta += step shr 1
            if ((nibble and 1) != 0) delta += step shr 2
            predictor = if ((nibble and 8) != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + indexTable[nibble]).coerceIn(0, 88)
            out[i] = predictor.toShort()
        }
        return out
    }

    private const val HEADER = 3
}
