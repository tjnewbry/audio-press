package io.github.tjnewbry.audiopress.bake

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tjnewbry.audiopress.bake.kokoro.GithubKokoroCatalog
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroModelFiles
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroPhonemizer
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroQuality
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVocab
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoices
import io.github.tjnewbry.audiopress.bake.kokoro.OnnxKokoroEngine
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue

/**
 * Runs each Kokoro quality at 1, 2, and 4 threads on a fixed 150-token input, and logs the real-time factor (seconds of
 * work per second of audio; below 1 is faster than real time) and the peak memory, under the log tag `KokoroBench` and
 * in `files/kokoro-bench-results.txt`.
 *
 * It needs the downloaded model files in the test app's `files/kokoro-bench/` folder (the four files of
 * [GithubKokoroCatalog]) and is skipped without them. Copy them in with `adb push` to `/data/local/tmp` and
 * `run-as io.github.tjnewbry.audiopress.bake.test cp ...`. The input is a fixed run of valid token ids, not words:
 * it measures speed and memory, not pronunciation. Run it on an emulator or a test device only.
 */
@RunWith(AndroidJUnit4::class)
class KokoroBenchmarkTest {
    private val voice: KokoroVoice = KokoroVoices.byId("af_bella")!!

    /** 150 token ids from the range of letters and punctuation, the same on every run. */
    private val tokens = IntArray(150) { i -> if (i % 7 == 6) 16 else 38 + (i * 5) % 25 }

    private val noPhonemes =
        object : KokoroPhonemizer {
            override fun phonemize(
                text: String,
                voice: KokoroVoice,
            ): String = error("the benchmark feeds token ids directly")
        }

    private fun peakRssMb(): Long =
        File("/proc/self/status")
            .readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLong()
            ?.div(1024) ?: -1

    @Test
    fun everyQualityAtOneTwoAndFourThreads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "kokoro-bench")
        val voices = File(folder, GithubKokoroCatalog.voices.fileName)
        assumeTrue("copy the Kokoro files to ${folder.path} to run the benchmark", voices.isFile)
        val report = StringBuilder("device: ${android.os.Build.MODEL}, ${Runtime.getRuntime().availableProcessors()} cores\n")
        var ran = 0
        for (quality in KokoroQuality.entries) {
            val model = File(folder, GithubKokoroCatalog.model(quality).fileName)
            if (!model.isFile) continue
            for (threads in listOf(1, 2, 4)) {
                val sampler = AtomicBoolean(true)
                var peakNativeMb = 0L
                val thread =
                    Thread {
                        while (sampler.get()) {
                            peakNativeMb = maxOf(peakNativeMb, Debug.getNativeHeapAllocatedSize() shr 20)
                            Thread.sleep(25)
                        }
                    }.apply { start() }
                val loadStart = System.nanoTime()
                OnnxKokoroEngine(KokoroModelFiles(model, voices, quality), KokoroVocab(emptyMap()), noPhonemes, threads).use { engine ->
                    val loadSeconds = (System.nanoTime() - loadStart) / 1e9
                    engine.run(tokens, voice, 1f) // warm-up
                    val times = mutableListOf<Double>()
                    var audioSeconds = 0.0
                    repeat(3) {
                        val start = System.nanoTime()
                        val samples = engine.run(tokens, voice, 1f)
                        times += (System.nanoTime() - start) / 1e9
                        audioSeconds = samples.size / OnnxKokoroEngine.SAMPLE_RATE.toDouble()
                    }
                    assertTrue(audioSeconds > 1.0, "the model made $audioSeconds s of audio")
                    val best = times.min()
                    val line =
                        "%s threads=%d load=%.1fs audio=%.1fs best=%.2fs rtf=%.2f peakNativeHeap=%dMB peakRss=%dMB".format(
                            quality.displayName,
                            threads,
                            loadSeconds,
                            audioSeconds,
                            best,
                            best / audioSeconds,
                            peakNativeMb,
                            peakRssMb(),
                        )
                    Log.i("KokoroBench", line)
                    report.append(line).append('\n')
                    ran++
                }
                sampler.set(false)
                thread.join()
            }
        }
        File(context.filesDir, "kokoro-bench-results.txt").writeText(report.toString())
        assumeTrue("none of the model files were in ${folder.path}", ran > 0)
    }
}
