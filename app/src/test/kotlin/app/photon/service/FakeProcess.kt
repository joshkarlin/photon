package app.photon.service

import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bridge process on in-memory pipes. [print] writes a line to its stdout
 * and [sent] reads what was written to its stdin. It exits when its stdin
 * closes, unless [hangsOnExit] (whatsmeow's disconnect stuck), when only
 * [destroyForcibly] ends it; [onStdinClosed] runs first either way.
 */
internal class FakeProcess(
    private val hangsOnExit: Boolean = false,
    private val onStdinClosed: (FakeProcess) -> Unit = {},
) : Process() {
    private val toBridge = Pipe.open()
    private val fromBridge = Pipe.open()
    private val exited = CountDownLatch(1)
    private val stdoutWriter = Channels.newOutputStream(fromBridge.sink()).bufferedWriter()
    private val stdout = Channels.newInputStream(fromBridge.source())
    private val stdin = object : FilterOutputStream(Channels.newOutputStream(toBridge.sink())) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() {
            super.close()
            onStdinClosed(this@FakeProcess)
            if (!hangsOnExit) exit()
        }
    }

    val sent = Channels.newInputStream(toBridge.source()).bufferedReader()

    /** How many times something took this process's stdout to read it. */
    val stdoutReaders = AtomicInteger()

    @Volatile var killed = false
        private set

    fun print(line: String) = synchronized(stdoutWriter) {
        stdoutWriter.write(line)
        stdoutWriter.newLine()
        stdoutWriter.flush()
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout.also { stdoutReaders.incrementAndGet() }
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = exited.await().let { 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
    override fun isAlive(): Boolean = exited.count > 0

    // SIGTERM: a bridge stuck in its disconnect doesn't act on it.
    override fun destroy() {}

    override fun destroyForcibly(): Process {
        killed = true
        exit()
        return this
    }

    /** The process ends: its stdout reaches EOF. */
    fun exit() {
        synchronized(stdoutWriter) {
            if (exited.count == 0L) return
            try { stdoutWriter.close() } catch (_: IOException) {}
            exited.countDown()
        }
    }
}
