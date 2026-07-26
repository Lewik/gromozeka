package com.gromozeka.shared.utils

fun ByteArray.sha256(): String {
    val bitLength = size.toLong() * 8
    val paddingSize = (56 - (size + 1) % 64 + 64) % 64
    val padded = ByteArray(size + 1 + paddingSize + 8)
    copyInto(padded)
    padded[size] = 0x80.toByte()
    repeat(8) { index ->
        padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
    }

    val state = intArrayOf(
        0x6a09e667,
        0xbb67ae85u.toInt(),
        0x3c6ef372,
        0xa54ff53au.toInt(),
        0x510e527f,
        0x9b05688cu.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )
    val words = IntArray(64)

    for (offset in padded.indices step 64) {
        repeat(16) { index ->
            val wordOffset = offset + index * 4
            words[index] =
                ((padded[wordOffset].toInt() and 0xff) shl 24) or
                    ((padded[wordOffset + 1].toInt() and 0xff) shl 16) or
                    ((padded[wordOffset + 2].toInt() and 0xff) shl 8) or
                    (padded[wordOffset + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val first = words[index - 15].rotateRight(7) xor
                words[index - 15].rotateRight(18) xor
                (words[index - 15] ushr 3)
            val second = words[index - 2].rotateRight(17) xor
                words[index - 2].rotateRight(19) xor
                (words[index - 2] ushr 10)
            words[index] = words[index - 16] + first + words[index - 7] + second
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        repeat(64) { index ->
            val upperSigma = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temporaryOne = h + upperSigma + choice + SHA256_CONSTANTS[index] + words[index]
            val lowerSigma = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporaryTwo = lowerSigma + majority

            h = g
            g = f
            f = e
            e = d + temporaryOne
            d = c
            c = b
            b = a
            a = temporaryOne + temporaryTwo
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    return state.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}

fun String.sha256(): String = encodeToByteArray().sha256()

private fun Int.rotateRight(bits: Int): Int =
    (this ushr bits) or (this shl (Int.SIZE_BITS - bits))

private val SHA256_CONSTANTS = intArrayOf(
    0x428a2f98,
    0x71374491,
    0xb5c0fbcfu.toInt(),
    0xe9b5dba5u.toInt(),
    0x3956c25b,
    0x59f111f1,
    0x923f82a4u.toInt(),
    0xab1c5ed5u.toInt(),
    0xd807aa98u.toInt(),
    0x12835b01,
    0x243185be,
    0x550c7dc3,
    0x72be5d74,
    0x80deb1feu.toInt(),
    0x9bdc06a7u.toInt(),
    0xc19bf174u.toInt(),
    0xe49b69c1u.toInt(),
    0xefbe4786u.toInt(),
    0x0fc19dc6,
    0x240ca1cc,
    0x2de92c6f,
    0x4a7484aa,
    0x5cb0a9dc,
    0x76f988da,
    0x983e5152u.toInt(),
    0xa831c66du.toInt(),
    0xb00327c8u.toInt(),
    0xbf597fc7u.toInt(),
    0xc6e00bf3u.toInt(),
    0xd5a79147u.toInt(),
    0x06ca6351,
    0x14292967,
    0x27b70a85,
    0x2e1b2138,
    0x4d2c6dfc,
    0x53380d13,
    0x650a7354,
    0x766a0abb,
    0x81c2c92eu.toInt(),
    0x92722c85u.toInt(),
    0xa2bfe8a1u.toInt(),
    0xa81a664bu.toInt(),
    0xc24b8b70u.toInt(),
    0xc76c51a3u.toInt(),
    0xd192e819u.toInt(),
    0xd6990624u.toInt(),
    0xf40e3585u.toInt(),
    0x106aa070,
    0x19a4c116,
    0x1e376c08,
    0x2748774c,
    0x34b0bcb5,
    0x391c0cb3,
    0x4ed8aa4a,
    0x5b9cca4f,
    0x682e6ff3,
    0x748f82ee,
    0x78a5636f,
    0x84c87814u.toInt(),
    0x8cc70208u.toInt(),
    0x90befffau.toInt(),
    0xa4506cebu.toInt(),
    0xbef9a3f7u.toInt(),
    0xc67178f2u.toInt(),
)
