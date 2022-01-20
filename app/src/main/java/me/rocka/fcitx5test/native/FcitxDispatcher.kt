package me.rocka.fcitx5test.native

import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureTimeMillis

class FcitxDispatcher(private val controller: FcitxController) : CoroutineScope by controller {

    // this is fcitx main thread
    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it).apply {
            name = "FcitxMain"
        }
    }.asCoroutineDispatcher()

    interface FcitxController : CoroutineScope {
        fun nativeStartup()
        fun nativeLoopOnce()
        fun nativeScheduleEmpty()
        fun nativeExit()
    }

    // not concurrent
    object Watchdog : CoroutineScope {
        private var installed: Job? = null
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        fun install() {
            if (installed != null)
                throw IllegalStateException("Watchdog has been installed!")
            installed = launch {
                delay(SINGLE_JOB_TIME_LIMIT)
                throw RuntimeException("Fcitx main thread had been blocked for $SINGLE_JOB_TIME_LIMIT ms!")
            }
        }

        fun teardown() {
            (installed ?: throw IllegalStateException("Watchdog has not been installed!")).cancel()
            installed = null
        }

        inline fun <T> withWatchdog(block: () -> T): T {
            install()
            try {
                return block()
            } finally {
                teardown()
            }
        }

        override val coroutineContext: CoroutineContext = dispatcher
    }

    private val lock = ReentrantLock()

    private val queue = ConcurrentLinkedQueue<Runnable>()

    private val _isRunning = AtomicBoolean(false)

    val isRunning: Boolean
        get() = _isRunning.get()

    fun start() = launch(internalDispatcher) {
        Timber.tag(this@FcitxDispatcher.javaClass.name).i("Start")
        if (_isRunning.compareAndSet(false, true)) {
            Timber.tag(this@FcitxDispatcher.javaClass.name).d("Calling native startup")
            controller.nativeStartup()
        }
        while (isRunning) {
            // blocking...
            lock.withLock {
                measureTimeMillis {
                    controller.nativeLoopOnce()
                }.let {
                    Timber.tag(this@FcitxDispatcher.javaClass.name)
                        .d("Finishing executing native loop once, took $it ms")
                }
            }
            Watchdog.withWatchdog {
                // do scheduled jobs
                measureTimeMillis {
                    while (true) {
                        val block = queue.poll() ?: break
                        block.run()
                    }
                }.let {
                    Timber.tag(this@FcitxDispatcher.javaClass.name)
                        .d("Finishing running scheduled jobs, took $it ms")
                }
            }
        }
        Timber.tag(this@FcitxDispatcher.javaClass.name).i("Calling native exit")
        controller.nativeExit()
    }


    fun stop(): List<Runnable> {
        Timber.i("Stop")
        return if (_isRunning.compareAndSet(true, false)) {
            cancel()
            val rest = queue.toList()
            queue.clear()
            rest
        } else emptyList()
    }

    private fun dispatch(block: Runnable) {
        queue.offer(block)
        // bypass nativeLoopOnce if no code is executing in native dispatcher
        if (lock.isLocked)
            controller.nativeScheduleEmpty()
    }

    suspend fun <T> dispatch(block: () -> T): T = suspendCoroutine {
        dispatch(Runnable {
            it.resume(block())
        })
    }

    companion object {
        const val SINGLE_JOB_TIME_LIMIT = 5000L
    }

}