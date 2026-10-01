package com.heizhu.weiqi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 对局音效播放器。
 *
 * ## 为什么用 SoundPool 而不是 MediaPlayer
 * MediaPlayer 每次播放都要走一遍解码器初始化，短音效会有明显延迟和卡顿。
 * SoundPool 把音频预先解码进内存，适合落子、提子这种几十到几百毫秒的
 * 高频短音。
 *
 * ## 音效文件
 * 由 `tools/gen_sfx.py` 程序合成（无版权素材依赖），16-bit PCM WAV，
 * 放在 `res/raw/`，合计约 180KB。
 *
 * ## 加载时机
 * SoundPool 的加载是**异步**的。首次调用时若尚未加载完成就直接播放会静默失败，
 * 所以这里用 [loadedIds] 记录加载完成的样本，未就绪时安静跳过 —— 宁可不响，
 * 也不能因为播不出声音而阻塞落子。
 */
class SoundPlayer(context: Context, private val isEnabled: () -> Boolean) {

    /** 可播放的音效 */
    enum class Sfx(val resName: String) {
        /** 己方落子 */
        STONE_PLACE("stone_place"),

        /** 对手落子（音量更低，听感上区分敌我） */
        STONE_AI("stone_ai"),

        /** 提子 */
        CAPTURE("stone_capture"),

        /** 悔棋 */
        UNDO("undo"),

        /** 非法落子 */
        ILLEGAL("illegal"),

        /** 获胜 */
        WIN("win"),

        /** 落败 */
        LOSE("lose"),
    }

    private val pool: SoundPool

    /** 音效 → SoundPool 样本 id */
    private val sampleIds = HashMap<Sfx, Int>()

    /** 已加载完成的样本 id */
    private val loadedIds = HashSet<Int>()

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        pool = SoundPool.Builder()
            .setMaxStreams(4)                 // 落子 + 提子可能叠在一起
            .setAudioAttributes(attributes)
            .build()

        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }

        for (sfx in Sfx.entries) {
            val resId = context.resources.getIdentifier(sfx.resName, "raw", context.packageName)
            if (resId != 0) {
                sampleIds[sfx] = pool.load(context, resId, 1)
            }
        }
    }

    /**
     * 播放音效。
     *
     * @param volume 0f~1f。对手落子用 0.7f 稍作压低。
     */
    fun play(sfx: Sfx, volume: Float = 1f) {
        if (!isEnabled()) return
        val sampleId = sampleIds[sfx] ?: return
        if (sampleId !in loadedIds) return    // 尚未加载完成，安静跳过
        pool.play(sampleId, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
