package bot.music.whiter

import io.github.mzdluo123.silk4j.AudioUtils
import kotlinx.coroutines.delay
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.FileSupported
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.Audio
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.info
import whiter.music.mider.dsl.MiderDSL
import whiter.music.mider.dsl.fromDsl
import java.io.FileFilter
import java.io.InputStream
import java.net.URL

object MidiProduce : KotlinPlugin(
    JvmPluginDescription(
        id = "bot.music.whiter.MidiProduce",
        name = "MidiProduce",
        version = "0.1.6",
    ) {
        author("whiterasbk")
    }
) {

    // todo 1. 出场自带 bgm ( 频率
    // todo 2. 相对音准小测试
    // todo 3. 隔群发送语音, 寻觅知音 (
    // todo 4. 增加乐器(
    // todo 5. 增加力度
    // todo 6. mider code for js
    // todo 7. 权限系统, 话说就发个语音有引入命令权限的必要吗 (

    private val cache = mutableMapOf<String, Audio>()
    val tmpDir = resolveDataFile("tmp")

    override fun onEnable() {
        logger.info { "MidiProduce loaded" }

        Config.reload()

        initTmpAndFormatTransfer()

        val process: suspend MessageEvent.() -> Unit = {
            try {
                generate()
                printHelp()
            } catch (e: Exception) {
                logger.error(e)
                subject.sendMessage("解析错误>${e::class.simpleName}>" + e.message)
            }
        }

        val botEvent = globalEventChannel().filter { it is BotEvent }

        botEvent.subscribeAlways<GroupMessageEvent>{
            process()
        }

        botEvent.subscribeAlways<FriendMessageEvent> {
            process()
        }
    }

    private fun initTmpAndFormatTransfer() {
        if (!tmpDir.exists()) tmpDir.mkdir()

        try {
            tmpDir.listFiles(FileFilter {
                it.extension == "so" ||
                        it.extension == "dll" ||
                        it.extension == "lib" ||
                        it.extension == "mp3" ||
                        it.extension == "silk" ||
                        it.extension == "wave" ||
                        it.extension == "wav" ||
                        it.extension == "amr" ||
                        it.extension == "mid" ||
                        it.extension == "midi" ||
                        it.extension == "mscz" ||
                        it.extension == "png" ||
                        it.extension == "pdf" ||
                        it.extension == "pcm"
            })?.forEach {
                it.delete()
            }
        } catch (e: Exception) {
            logger.error("清理缓存失败")
            logger.error(e)
        }

        if (Config.formatMode.contains("silk4j")) {
            try {
                AudioUtils.init(tmpDir)
            } catch (e: Exception) {
                logger.error("silk4j 加载失败, 将无法生成语音")
                logger.error(e)
            }
        }

        if (Config.formatMode.contains("timidity") && Config.timidityConvertCommand.isBlank()) {
            logger.error("timidity 命令未配置, 将无法生成语音(wav)")
        }

        if (Config.formatMode.contains("ffmpeg") && Config.ffmpegConvertCommand.isBlank()) {
            logger.error("ffmpeg 命令未配置, 将无法生成语音(mp3)")
        }
    }

    private suspend fun MessageEvent.printHelp() {
        if (message.content == ">!help>") {
            subject.sendMessage(Config.help)
        }
    }

    private suspend fun MessageEvent.generate() {

        val startRegex = Regex(">(g|f|\\d+b)((;[-+b#]?[A-G](min|maj|major|minor)?)|(;\\d)|(;img)|(;pdf)|(;mscz)|(;midi))*>")
        val cmdRegex = Regex("${startRegex.pattern}[\\S\\s]+")

        matchRegex(cmdRegex) { msg ->
            if (Config.cache && msg in cache) {
                cache[msg]?.let {
                    ifDebug("send from cache")
                    subject.sendMessage(it)
                } ?: throw Exception("启用了缓存但是缓存中没有对应的语音消息")
            } else {
                time {
                    logger.info("sounds begin")

                    val noteLists = msg.split(startRegex).toMutableList()
                    noteLists.removeFirst()
                    val configParts = startRegex.findAll(msg).map { it.value.replace(">", "") }.toList()

                    val isUploadMidi = "midi" in configParts.joinToString(" ")
                    var isRenderingNotation = false
                    var notationType: NotationType? = null

                    val dslBlock: MiderDSL.() -> Unit = {

                        val macroConfig = MacroConfiguration {

                            recursionLimit(Config.recursionLimit)

                            loggerInfo { logger.info(it) }
                            loggerError {
                                if (Config.macroUseStrictMode) throw it else logger.error(it)
                            }

                            fetchMethod {
                                if (it.startsWith("http://") || it.startsWith("https://") || it.startsWith("ftp://"))
                                    URL(it).openStream().reader().readText()
                                else
                                    resolveDataFile(it.replace("file:", "")).readText()
                            }
                        }
                        val changeBpm = { tempo: Int -> bpm = tempo }

                        noteLists.forEachIndexed { index, content ->

                            track {
                                var mode = ""
                                var defaultPitch = 4

                                defaultNoteDuration = 1

                                configParts[index].split(";").forEach {
                                    if (it == "f") {
                                        defaultPitch = 3
                                    } else if (it.matches(Regex("\\d+b"))) {
                                        changeBpm(it.replace("b", "").toInt())
                                    } else if (it.matches(Regex("[-+b#]?[A-G](min|maj|major|minor)?"))) {
                                        mode = it
                                    } else if (it.matches(Regex("\\d"))) {
                                        defaultPitch = it.toInt()
                                    } else if (it.matches(Regex("img"))) {
                                        isRenderingNotation = true
                                        notationType = NotationType.PNGS
                                    } else if (it.matches(Regex("pdf"))) {
                                        isRenderingNotation = true
                                        notationType = NotationType.PDF
                                    } else if (it.matches(Regex("mscz"))) {
                                        isRenderingNotation = true
                                        notationType = NotationType.MSCZ
                                    }
                                }

                                val sequence = macro(content, macroConfig)

                                val isStave =
                                    Regex("[c-gaA-G]").find(sequence) != null || Regex("(\\s*b\\s*)+").matches(sequence)

                                val rendered = toInMusicScoreList(
                                    sequence.let {
                                        if (isStave && Config.isBlankReplaceWith0) it else
                                            it.trim().replace(Regex("( {2}| \\| )"), "0")
                                    },
                                    isStave = isStave,
                                    pitch = defaultPitch, useMacro = false
                                )

                                ifUseMode(mode) {
                                    val stander = toMiderStanderNoteString(rendered)
                                    if (stander.isNotBlank()) !stander
                                }

                                ifDebug { logger.info("track: ${index + 1}"); debug() }
                            }

                        }
                    }

                    val midiStream: InputStream = fromDsl(dslBlock).inStream()

                    if (isRenderingNotation) {
                        // 渲染 乐谱
                        if (isRenderingNotation) {
                            val midi = AudioUtilsGetTempFile("mid")
                            midi.writeBytes(midiStream.readAllBytes())
                            val mscz = convert2MSCZ(midi)

                            when (notationType) {
                                NotationType.PNGS -> {
                                    convert2PNGS(mscz).forEach { png ->
                                        logger.info("$>>${png.name}")

                                        png.toExternalResource().use {
                                            val img = subject.uploadImage(it)
                                            subject.sendMessage(img)
                                            delay(100)
                                        }
                                    }
                                }

                                NotationType.PDF -> {
                                    if (subject is FileSupported) {
                                        val pdf = convert2PDF(mscz)
                                        pdf.toExternalResource().use {
                                            (subject as FileSupported).files.uploadNewFile(pdf.name, it)
                                        }
                                    } else subject.sendMessage("打咩")
                                }

                                NotationType.MSCZ -> {
                                    if (subject is FileSupported) {
                                        mscz.toExternalResource().use {
                                            (subject as FileSupported).files.uploadNewFile(mscz.name, it)
                                        }
                                    } else subject.sendMessage("打咩")
                                }

                                else -> throw Exception("plz provide the output format")
                            }
                        }
                    } else if (isUploadMidi && subject is FileSupported) {
                        midiStream.toExternalResource().use {
                            (subject as FileSupported).files.uploadNewFile(
                                "generate-${System.currentTimeMillis()}.mid",
                                it
                            )
                        }
                    } else {
                        val stream = generateAudioStreamByFormatMode(midiStream)
                        when (this) {

                            is GroupMessageEvent -> {

                                if (stream.available() > Config.uploadSize) {
                                    stream.toExternalResource().use {
                                        group.files.uploadNewFile(
                                            "generate-${System.currentTimeMillis()}.mp3",
                                            it
                                        )
                                    }
                                } else {
                                    stream.toExternalResource().use {
                                        val audio = group.uploadAudio(it)
                                        group.sendMessage(audio)
                                        if (Config.cache) cache[msg] = audio
                                    }
                                }
                            }

                            is FriendMessageEvent -> {
                                if (stream.available() > Config.uploadSize) {
                                    friend.sendMessage("生成的语音过大且bot不能给好友发文件")
                                } else {
                                    stream.toExternalResource().use {
                                        val audio = friend.uploadAudio(it)
                                        friend.sendMessage(audio)
                                        if (Config.cache) cache[msg] = audio
                                    }
                                }
                            }

                            else -> throw Exception("打咩")
                        }
                    }
                }
            }
        }
    }

    private fun MiderDSL.ifUseMode(mode: String, block: MiderDSL.()-> Unit) {
        if (mode.isNotBlank()) {
            useMode(mode) {
                block()
            }
        } else block()
    }
}

enum class NotationType {
    PNGS, MSCZ, PDF
}

object Config : AutoSavePluginConfig("config") {

    @ValueDescription("ffmpeg 转换命令 (不使用 ffmpeg 也可以, 只要能完成 wav 到 mp3 的转换就行, {{input}} 和 {{output}} 由 插件提供不需要修改")
    val ffmpegConvertCommand by value("ffmpeg -i {{input}} -acodec libmp3lame -ab 256k {{output}}")
    @ValueDescription("timidity 转换命令 (不使用 timidity 也可以, 只要能完成 mid 到 wav 的转换就行")
    val timidityConvertCommand by value("timidity {{input}} -Ow -o {{output}}")

    @ValueDescription("muse score 从 .mid 转换到 .mscz")
    val mscoreConvertMidi2MSCZCommand by value("MuseScore3 {{input}} -o {{output}}")

    @ValueDescription("muse score 从 .mid 转换到 .pdf")
    val mscoreConvertMSCZ2PDFCommand by value("MuseScore3 {{input}} -o {{output}}")

    @ValueDescription("muse score 从 .mid 转换到 .png 序列")
    val mscoreConvertMSCZ2PNGSCommand by value("MuseScore3 {{input}} -o {{output}}")

    @ValueDescription("include 最大深度")
    val recursionLimit by value(50)
    @ValueDescription("silk 比特率(吧")
    val silkBitsRate by value(24000)
    @ValueDescription("是否启用缓存")
    val cache by value(true)

    @ValueDescription("格式转换输出 可选的有: \n" +
            "internal->java-lame(默认)\n" +
            "internal->java-lame->silk4j\n" +
            "timidity->ffmpeg\n" +
            "timidity->ffmpeg->silk4j\n" +
            "timidity->java-lame\n" +
            "timidity->java-lame->silk4j\n"
    )
    val formatMode by value("internal->java-lame")
    @ValueDescription("宏是否启用严格模式")
    val macroUseStrictMode by value(true)
    @ValueDescription("是否启用调试")
    val debug by value(false)
    @ValueDescription("是否启用空格替换")
    val isBlankReplaceWith0 by value(true)
    @ValueDescription("量化深度 理论上越大生成 mp3 的质量越好, java-lame 给出的值是 256")
    val quality by value(64)
    @ValueDescription("超过这个大小则自动改为文件上传")
    val uploadSize by value(1153433L)
    @ValueDescription("帮助信息 (更新版本时记得要删掉这一行)")
    val help by value("""
# 命令格式 (一个命令代表一条轨道)
>bpm[;mode][;pitch][;midi][;img][;pdf][;mscz]>音名序列 | 唱名序列
bpm: 速度, 必选, 格式是: 数字 + b, 如 120b, 默认可以用 g 或者 f 代替
mode: 调式, 可选, 格式是 b/#/-/+ 调式名, 如 Cminor, -Emaj, bC
pitch: 音域(音高), 可选, 默认为 4
midi: 是否仅上传 midi 文件, 可选
img: 是否仅上传 png 格式的乐谱
pdf: 是否仅上传 pdf 文件, 可选
mscz: 是否仅上传 mscz 文件, 可选
音名序列的判断标准是序列里是否出现了 c~a 或 C~B 中任何一个字符

# 示例
>g>1155665  4433221  5544332  5544332
等同于
>g>ccggaag+ffeeddc+ggffeed+ggffeed
等同于
>g>c~g~^~v+f~v~v~v+(repeat 2:g~v~v~v+) (酌情使用

# 公用规则 (如无特殊说明均使用在唱名或音名后, 并可叠加使用)
 # : 升一个半音, 使用在音名或唱名前
 ${'$'} : 降一个半音, 使用在音名或唱名前
 + : 时值变为原来的两倍
 - : 时值变为原来的一半
 . : 时值变为原来的一点五倍
 : : 两个以上音符组成一个和弦
 ~ : 克隆上一个音符
 ^ : 克隆上一个音符, 并升高 1 度
 v : 克隆上一个音符, 并降低 1 度
 ↑ : 升高一个八度
 ↓ : 降低一个八度
 & : 还原符号
类似的用法还有 m-w, n-u, i-!, q-p, s-z 升高或降低度数在 ^-v 的基础上逐步递增或递减

# 如果是音名序列则以下规则生效
a~g: A4~G4
A~G: A5~G5
 O : 二分休止符 
 o : 四分休止符 
0-9: 手动修改音域

# 如果是唱名序列则以下规则生效
1~7: C4~B4
 0 : 四分休止符
 i : 升高一个八度
 ! : 降低一个八度
 b : 降低一个半音, 使用在唱名前
 * : 后接一个一位数字表示重复次数
 
# 宏
目前可用的宏有
1. (def symbol=note sequence) 定义一个音符序列
2. (def symbol:note sequence) 定义一个音符序列, 并在此处展开
3. (=symbol) 展开 symbol 对应音符序列
4. (include path) 读取 path 代表的资源并展开
5. (repeat times: note sequence) 将音符序列重复 times 次
6. (ifdef symbol: note sequence) 如果定义了 symbol 则展开
7. (if!def symbol: note sequence) 如果未定义 symbol 则展开
8. (macro name param1[,params]: note sequence @[param1]) 定义宏
9. (!name arg1[,arg2]) 展开宏
目前宏均不可嵌套使用
""".trim())
}