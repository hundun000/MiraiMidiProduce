package bot.music.whiter

import io.github.mzdluo123.silk4j.AudioUtils
import io.github.mzdluo123.silk4j.SilkCoder
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.content
import net.sourceforge.lame.lowlevel.LameEncoder
import net.sourceforge.lame.mp3.Lame
import net.sourceforge.lame.mp3.MPEGMode
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.ExecuteWatchdog
import org.apache.commons.exec.PumpStreamHandler
import whiter.music.mider.dsl.MiderDSL
import whiter.music.mider.dsl.fromDsl
import java.io.*
import java.nio.charset.Charset
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.pow


suspend fun MessageEvent.matchRegex(reg: Regex, block: suspend (String) -> Unit) {
    if (message.content.matches(reg)) {
        block(message.content)
    }
}

suspend fun MessageEvent.matchRegex(reg: String, block: suspend (String) -> Unit) = matchRegex(Regex(reg), block)

fun midi2mp3Stream(USE_VARIABLE_BITRATE: Boolean = false, GOOD_QUALITY_BITRATE: Int = 256, midiStream: InputStream): ByteArrayInputStream {
    val audioInputStream = AudioSystem.getAudioInputStream(midiStream)
    return wave2mp3Stream(audioInputStream, USE_VARIABLE_BITRATE, GOOD_QUALITY_BITRATE)
}

fun wave2mp3Stream(audioInputStream: AudioInputStream, USE_VARIABLE_BITRATE: Boolean = false, GOOD_QUALITY_BITRATE: Int = 256): ByteArrayInputStream {
    val encoder = LameEncoder(
        audioInputStream.format,
        GOOD_QUALITY_BITRATE,
        MPEGMode.STEREO,
        Lame.QUALITY_HIGHEST,
        USE_VARIABLE_BITRATE
    )

    val mp3 = ByteArrayOutputStream()
    val inputBuffer = ByteArray(encoder.pcmBufferSize)
    val outputBuffer = ByteArray(encoder.pcmBufferSize)
    var bytesRead: Int
    var bytesWritten: Int
    while (0 < audioInputStream.read(inputBuffer).also { bytesRead = it }) {
        bytesWritten = encoder.encodeBuffer(inputBuffer, 0, bytesRead, outputBuffer)
        mp3.write(outputBuffer, 0, bytesWritten)
    }

    encoder.close()
    return ByteArrayInputStream(mp3.toByteArray())
}

fun ifDebug(block: ()-> Unit) {
    if (Config.debug) block()
}

fun ifDebug(info: String) {
    if (Config.debug) MidiProduce.logger.info(info)
}

fun noteBaseOffset(note: String): Int {
    return when (note) {
        "C", "#B" -> 0
        "#C", "bD" -> 1
        "D" -> 2
        "#D", "bE" -> 3
        "E", "bF" -> 4
        "F", "#E" -> 5
        "#F", "bG" -> 6
        "G" -> 7
        "#G", "bA" -> 8
        "A" -> 9
        "#A", "bB" -> 10
        "B", "bC" -> 11
        else -> throw Exception("no such note $note")
    }
}

fun noteNameFromCode(code: Int): String {
    return when(code % 12) {
        0 -> "C"
        1 -> "#C"
        2 -> "D"
        3 -> "#D"
        4 -> "E"
        5 -> "F"
        6 -> "#F"
        7 -> "G"
        8 -> "#G"
        9 -> "A"
        10 -> "#A"
        11 -> "B"
        else -> throw Exception("no such note code: $code")
    }
}

fun charCount(str: CharSequence, char: Char): Int {
    return str.filter { it == char }.count()
}

fun deriveInterval(index: Int, scale: Array<Int> = arrayOf(2, 2, 1, 2, 2, 2, 1)): Int {
    var sum = 0
    for (i in 0 until index) {
        sum += scale[i]
    }
    return sum
}

fun nextNoteIntervalInMajorScale(code: Int): Int {
    return when(code % 12) {
        0 -> 2  // C
        1 -> 2  // C#
        2 -> 2  // D
        3 -> 2  // D#
        4 -> 1  // E
        5 -> 2  // F
        6 -> 2  // F#
        7 -> 2  // G
        8 -> 2  // G#
        9 -> 2  // A
        10 -> 2 // A#
        11 -> 1 // B
        else -> 2
    }
}

fun previousNoteIntervalInMajorScale(code: Int): Int {
    return when(code % 12) {
        0 -> 1  // C
        1 -> 2  // C#
        2 -> 2  // D
        3 -> 2  // D#
        4 -> 2  // E
        5 -> 1  // F
        6 -> 2  // F#
        7 -> 2  // G
        8 -> 2  // G#
        9 -> 2  // A
        10 -> 2 // A#
        11 -> 2 // B
        else -> 2
    }
}

fun Long.autoTimeUnit(): String {
    return if (this < 1000) {
        "${this}ms"
    } else if (this in 1000..59999) {
        "${ String.format("%.2f", this.toFloat() / 1000) }s"
    } else {
        "${ this / 60_000 }m${ (this % 60_000) / 1000 }s"
    }
}

suspend fun time(block: suspend () -> Unit) {
    if (Config.debug) {
        val startCountingTime = System.currentTimeMillis()
        block()
        val useTime = System.currentTimeMillis() - startCountingTime
        MidiProduce.logger.info("生成用时: ${useTime.autoTimeUnit()}")
    } else block()
}

fun generateAudioStreamByFormatMode(midiStream: InputStream): InputStream {
    return when (Config.formatMode) {
        "internal->java-lame->silk4j" -> {
            ifDebug("using: internal->java-lame->silk4j")
            val audioInputStream = midi2mp3Stream(GOOD_QUALITY_BITRATE = Config.quality, midiStream = midiStream)
            val silk = AudioUtils.mp3ToSilk(audioInputStream, Config.silkBitsRate)
            silk.deleteOnExit()
            silk.inputStream()
        }

        "internal->silk4j" -> {
            // todo fix sampleRate 和 bitRate 怎么也对不上的问题
            ifDebug("using: internal->silk4j")
            val pcmStream = AudioSystem.getAudioInputStream(midiStream)
            val sampleRate = pcmStream.format.sampleRate.toInt()
            val bitRate = pcmStream.format.sampleSizeInBits
            val pcmFile = AudioUtilsGetTempFile("pcm").let { it.writeBytes(pcmStream.readAllBytes()); it }
            AudioUtilsPcmToSilk(pcmFile, sampleRate, bitRate).inputStream()
        }

        "timidity->ffmpeg" -> {
            ifDebug("using: timidity->ffmpeg")
            val wav = timidityConvert(midiStream)
            val mp3 = ffmpegConvert(wav.inputStream())
            mp3.inputStream()
        }

        "timidity->ffmpeg->silk4j" -> {
            ifDebug("timidity->ffmpeg->silk4j")
            val wav = timidityConvert(midiStream)
            val mp3 = ffmpegConvert(wav.inputStream())
            val silk = AudioUtils.mp3ToSilk(mp3, Config.silkBitsRate)
            silk.deleteOnExit()
            silk.inputStream()
        }

        "timidity->silk4j" -> {
            ifDebug("using: timidity->silk4j")
            TODO("not yet implement: 暂未支持操作")
        }

        "timidity->java-lame" -> {
            ifDebug("using: timidity->java-lame")
            val wav = timidityConvert(midiStream)
            wave2mp3Stream(AudioSystem.getAudioInputStream(wav), GOOD_QUALITY_BITRATE = Config.quality)
        }

        "timidity->java-lame->silk4j" -> {
            ifDebug("using: timidity->java-lame->silk4j")
            val wav = timidityConvert(midiStream)
            val mp3Stream = wave2mp3Stream(AudioSystem.getAudioInputStream(wav), GOOD_QUALITY_BITRATE = Config.quality)
            val silk = AudioUtils.mp3ToSilk(mp3Stream, Config.silkBitsRate)
            silk.deleteOnExit()
            silk.inputStream()
        }

        "muse-score" -> {
            ifDebug("using: muse-score")
            // support instrument
            museScoreConvert(midiStream).inputStream()
        }

        "muse-score->silk4j" -> {
            ifDebug("using: muse-score->silk4j")
            // support instrument
            val silk = AudioUtils.mp3ToSilk(museScoreConvert(midiStream).inputStream(), Config.silkBitsRate)
            silk.deleteOnExit()
            silk.inputStream()
        }

        // internal->java-lame 或者默认情况
        else -> {
            ifDebug("using: internal->java-lame")
            midi2mp3Stream(GOOD_QUALITY_BITRATE = Config.quality, midiStream = midiStream)
        }
    }
}

fun generateAudioStreamByFormatMode(block: MiderDSL.() -> Any): InputStream = generateAudioStreamByFormatMode(fromDsl(block).inStream())

fun timidityConvert(midiFileStream: InputStream): File {
    val midiFile = AudioUtilsGetTempFile("mid")
    midiFile.writeBytes(midiFileStream.readAllBytes())
    val outputFile = AudioUtilsGetTempFile("wav")

    val result = Config.timidityConvertCommand
        .replace("{{input}}", midiFile.name)
        .replace("{{output}}", outputFile.name)
        .execute()

    if (!outputFile.exists() || outputFile.length() == 0L) throw Exception(result.second)
    ifDebug(result.first)

    return outputFile
}

fun ffmpegConvert(midiFileStream: InputStream): File {
    val wavFile = AudioUtilsGetTempFile("wav")
    wavFile.writeBytes(midiFileStream.readAllBytes())
    val outputFile = AudioUtilsGetTempFile("mp3")

    val result = Config.ffmpegConvertCommand
        .replace("{{input}}", wavFile.name)
        .replace("{{output}}", outputFile.name)
        .execute()

    if (!outputFile.exists() || outputFile.length() == 0L) throw Exception(result.second)
    ifDebug(result.first)

    return outputFile
}

fun museScoreConvert(midiFileStream: InputStream): File {
    val midiFile = AudioUtilsGetTempFile("mid")
    midiFile.writeBytes(midiFileStream.readAllBytes())
    return convertUsingConfigCommand(Config.mscoreConvertMidi2Mp3Command, midiFile, "mp3")
}

fun convert2MSCZ(midi: File): File {
   return convertUsingConfigCommand(Config.mscoreConvertMidi2MSCZCommand, midi, "mscz")
}

fun convert2PDF(midi: File): File {
   return convertUsingConfigCommand(Config.mscoreConvertMSCZ2PDFCommand, midi, "pdf")
}

fun convert2PNGS(midi: File): List<File> {
    val outputSample = AudioUtilsGetTempFile("png")
    outputSample.writeText("大弦嘈嘈如急雨, 小弦切切如私语")
    convertUsingConfigCommand(Config.mscoreConvertMSCZ2PNGSCommand, midi, outputSample)

    val result = outputSample.parentFile.listFiles(FileFilter {
        it.name.startsWith(outputSample.nameWithoutExtension) && it != outputSample
    }) ?: throw Exception("convert to pngs failed")

    return result.toList().sorted()
}

private fun convertUsingConfigCommand(usingCommand: String, inputFile: File, outputFile: File): File {
    val result = usingCommand
        .replace("{{input}}", inputFile.name)
        .replace("{{output}}", outputFile.name)
        .execute()

    if (!outputFile.exists() || outputFile.length() == 0L) throw Exception(result.second)

    ifDebug(result.first)

    return outputFile
}

private fun convertUsingConfigCommand(usingCommand: String, inputFile: File, outputFileExtension: String): File {
    return convertUsingConfigCommand(usingCommand, inputFile, AudioUtilsGetTempFile(outputFileExtension))
}

@Throws(IOException::class)
private fun AudioUtilsPcmToSilk(pcmFile: File, sampleRate: Int, bitRate: Int = 24000): File {
    if (!pcmFile.exists() || pcmFile.length() == 0L) {
        throw IOException("文件不存在或为空")
    }
    val silkFile = AudioUtilsGetTempFile("silk")
    SilkCoder.encode(pcmFile.absolutePath, silkFile.absolutePath, sampleRate, bitRate)
    return silkFile
}

fun AudioUtilsGetTempFile(type: String, autoClean: Boolean = true): File {
    val fileName = "mirai_audio_${type}_${System.currentTimeMillis()}.$type"
    return File(MidiProduce.tmpDir, fileName).let { if (autoClean) it.deleteOnExit(); it }
}

fun String.execute(charset: String, timeout: Long = Config.commandTimeout, workingDir: File = MidiProduce.tmpDir) = this.execute(Charset.forName(charset), timeout , workingDir)

fun String.execute(charset: Charset = Charset.forName("utf-8"), timeout: Long = Config.commandTimeout, workingDir: File = MidiProduce.tmpDir): Pair<String, String> {
    //接收正常结果流
    val outputStream = ByteArrayOutputStream()
    //接收异常结果流
    val errorStream = ByteArrayOutputStream()
    val commandline: CommandLine = CommandLine.parse(this)
    val exec = DefaultExecutor()
    exec.workingDirectory = workingDir
    exec.setExitValues(null)
    val watchdog = ExecuteWatchdog(timeout)
    exec.watchdog = watchdog
    val streamHandler = PumpStreamHandler(outputStream, errorStream)
    exec.streamHandler = streamHandler
    exec.execute(commandline)
    //不同操作系统注意编码，否则结果乱码
    val out = outputStream.toString(charset)
    val error = errorStream.toString(charset)
    return out to error
}

// 求给定 index 之后
fun String.nextOnlyInt(index: Int, maxBit: Int): Int {
    var sum = 0
    var count = 0
    for (i in 1 .. maxBit) {
        if (index + i < length) {
            val nextChar = this[index + i]
            if (nextChar in '0'..'9') {
                sum = sum * 10 + (nextChar.code - 48)
                count ++
            } else break
        }
    }

    if (count == 0) throw Exception("there's no integer found after char '${this[index]}', index: $index or maxCount < 1")

    return sum
}