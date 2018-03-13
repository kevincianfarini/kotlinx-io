package kotlinx.io.core


internal inline fun BufferView.decodeASCII(consumer: (Char) -> Boolean): Boolean {
    for (i in 0 until readRemaining) {
        val v = readByte().toInt() and 0xff
        if (v and 0x80 != 0 || !consumer(v.toChar())) {
            pushBack(1)
            return false
        }
    }

    return true
}

/**
 * Decodes all the bytes to utf8 applying every character on [consumer] until or consumer return `false`.
 * If a consumer returned false then a character will be pushed back (including all surrogates will be pushed back as well)
 * and [decodeUTF8] returns -1
 * @return number of bytes required to decode incomplete utf8 character or 0 if all bytes were processed
 * or -1 if consumer rejected loop
 */
internal inline fun BufferView.decodeUTF8(consumer: (Char) -> Boolean): Int {
    var byteCount = 0
    var value = 0
    var lastByteCount = 0

    while (canRead()) {
        val v = readByte().toInt() and 0xff
        when {
            v and 0x80 == 0 -> {
                if (byteCount != 0) malformedByteCount(byteCount)
                if (!consumer(v.toChar())) {
                    pushBack(1)
                    return -1
                }
            }
            byteCount == 0 -> {
                // first unicode byte

                var mask = 0x80
                value = v

                for (i in 1..6) { // TODO do we support 6 bytes unicode?
                    if (value and mask != 0) {
                        value = value and mask.inv()
                        mask = mask shr 1
                        byteCount++
                    } else {
                        break
                    }
                }

                lastByteCount = byteCount
                byteCount--

                if (byteCount > readRemaining) {
                    pushBack(1) // return one byte back
                    return lastByteCount
                }
            }
            else -> {
                // trailing unicode byte
                value = (value shl 6) or (v and 0x7f)
                byteCount--

                if (byteCount == 0) {
                    if (isBmpCodePoint(value)) {
                        if (!consumer(value.toChar())) {
                            pushBack(lastByteCount)
                            return -1
                        }
                    } else if (!isValidCodePoint(value)) {
                        malformedCodePoint(value)
                    } else {
                        if (!consumer(highSurrogate(value).toChar()) ||
                                !consumer(lowSurrogate(value).toChar())) {
                            pushBack(lastByteCount)
                            return -1
                        }
                    }

                    value = 0
                }
            }
        }
    }

    return 0
}

private fun malformedByteCount(byteCount: Int): Nothing = throw MalformedUTF8InputException("Expected $byteCount more character bytes")
private fun malformedCodePoint(value: Int): Nothing = throw IllegalArgumentException("Malformed code-point $value found")

private const val MaxCodePoint = 0X10ffff
private const val MinLowSurrogate = 0xdc00
private const val MinHighSurrogate = 0xd800
private const val MinSupplementary = 0x10000
private const val HighSurrogateMagic = MinHighSurrogate - (MinSupplementary ushr 10)

private fun isBmpCodePoint(cp: Int) = cp ushr 16 == 0
private fun isValidCodePoint(codePoint: Int) = codePoint <= MaxCodePoint
private fun lowSurrogate(cp: Int) = (cp and 0x3ff) + MinLowSurrogate
private fun highSurrogate(cp: Int) = (cp ushr 10) + HighSurrogateMagic

class MalformedUTF8InputException(message: String) : Exception(message)