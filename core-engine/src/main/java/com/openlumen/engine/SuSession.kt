package com.openlumen.engine

import android.util.Log
import java.io.IOException
import java.io.Writer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One root shell shared by every `su` call made inside [Su.withSession].
 *
 * A colour fade is a run of applies a few tens of milliseconds apart, and each
 * apply used to start its own `su`. Starting `su` costs about as much as the
 * work it then does, so a fade paid for everything twice and could not run at
 * a frame rate that looks smooth. Inside a session the shell is started once,
 * on the first call that needs it, and each call is a few lines written to its
 * standard input.
 *
 * Calls behave as they do outside a session: the command runs in a subshell,
 * so `exit` and `set -e` end only that command, standard input is
 * `/dev/null`, so a command cannot read the commands queued behind it, and
 * the exit code and combined output come back the same way. A call that
 * times out or loses the shell breaks the session, and every later call in it
 * starts its own `su` again, as before.
 */
internal class SuSession(
    private val command: List<String> = listOf("su")
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SuSession>

    private var shell: RootShell? = null
    private var broken = false

    /** The open shell, or null when this session cannot provide one. Blocking. */
    @Synchronized
    fun shellOrNull(): RootShell? {
        if (broken) return null
        shell?.let { if (it.usable) return it }
        shell?.close()
        shell = null
        val started = RootShell.start(command)
        if (started == null) {
            broken = true
            return null
        }
        shell = started
        return started
    }

    @Synchronized
    fun markBroken() {
        broken = true
        shell?.close()
        shell = null
    }

    /** Blocking; at most [RootShell.CLOSE_WAIT_MS] plus a forced kill. */
    @Synchronized
    fun close() {
        broken = true
        shell?.close()
        shell = null
    }
}

internal class RootShell private constructor(private val process: Process) {
    private val marker = "__openlumen_" + UUID.randomUUID().toString().replace("-", "") + "__"
    private val lines = LinkedBlockingQueue<String>()
    private val writer: Writer = process.outputStream.bufferedWriter()
    @Volatile var usable: Boolean = true
        private set

    private val reader = Thread({
        try {
            process.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    lines.put(line)
                }
            }
        } catch (_: IOException) {
            // Closed by close() or by the process ending.
        }
        lines.put(END)
    }, "OpenLumen-su-session").apply {
        isDaemon = true
        start()
    }

    /** Run [script] and wait for its exit code. Blocking. */
    @Synchronized
    fun run(script: String, timeoutMs: Long): Su.SuResult {
        if (!usable) return Su.SuResult(-1, "", "session closed")
        try {
            writer.write("(\n")
            writer.write(script)
            writer.write("\n) </dev/null 2>&1; echo \"$marker \$?\"\n")
            writer.flush()
        } catch (e: IOException) {
            usable = false
            return Su.SuResult(-1, "", "session write failed: ${e.message}")
        }
        val out = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val line = if (remaining > 0) lines.poll(remaining, TimeUnit.NANOSECONDS) else null
            if (line == null) {
                Log.w(TAG, "session command timed out after ${timeoutMs}ms: ${script.take(120)}")
                usable = false
                close()
                return Su.SuResult(-1, out.toString().trim(), "")
            }
            if (line === END) {
                usable = false
                return Su.SuResult(-1, out.toString().trim(), "session ended")
            }
            val at = line.indexOf(marker)
            if (at >= 0) {
                appendBounded(out, line.substring(0, at))
                val code = line.substring(at + marker.length).trim().toIntOrNull() ?: -1
                return Su.SuResult(code, out.toString().trim(), "")
            }
            appendBounded(out, line)
            appendBounded(out, "\n")
        }
    }

    fun close() {
        usable = false
        runCatching {
            writer.write("exit\n")
            writer.flush()
        }
        runCatching { process.outputStream.close() }
        val exited = runCatching { process.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) {
            runCatching { process.destroy() }
            val gone = runCatching { process.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!gone) runCatching { process.destroyForcibly() }
        }
        runCatching { process.inputStream.close() }
        runCatching { reader.join(CLOSE_WAIT_MS) }
    }

    private fun appendBounded(out: StringBuilder, text: String) {
        val room = MAX_OUTPUT_CHARS - out.length
        if (room <= 0) return
        out.append(text, 0, minOf(room, text.length))
    }

    companion object {
        private const val TAG = "OpenLumen/SuSession"
        const val CLOSE_WAIT_MS = 250L
        private const val MAX_OUTPUT_CHARS = 16 * 1024

        /** Identity-compared end-of-stream marker; never equal to a real line. */
        private val END: String = StringBuilder("<end>").toString()

        /** [command] is `su` in the app; tests start a plain `sh` instead. */
        fun start(command: List<String> = listOf("su")): RootShell? = try {
            RootShell(ProcessBuilder(command).redirectErrorStream(true).start())
        } catch (e: IOException) {
            Log.d(TAG, "su not on PATH (${e.message})")
            null
        }
    }
}
