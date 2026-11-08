package com.xayah.core.rootservice.impl

import android.os.IBinder
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.xayah.core.util.withLog
import java.io.File
import java.io.FileNotFoundException

internal class PackagePauses {
    private data class Process(val pid: Int, val startTime: Long)
    private class Session(val death: IBinder.DeathRecipient, val processes: MutableSet<Process> = mutableSetOf())

    private val sessions = mutableMapOf<IBinder, Session>()
    private var closed = false

    @Synchronized
    fun pause(owner: IBinder, pids: Collection<Int>) {
        check(!closed && owner !in sessions)
        if (pids.isEmpty()) return
        val session = Session(IBinder.DeathRecipient { runCatching { resume(owner) }.withLog() })
        owner.linkToDeath(session.death, 0)
        sessions[owner] = session
        try {
            for (pid in pids) {
                val (state, startTime) = stat(pid) ?: continue
                val process = Process(pid, startTime)
                if (sessions.values.none { process in it.processes }) {
                    if (state == 'T' || state == 't' || !signal(pid, OsConstants.SIGSTOP)) continue
                }
                session.processes.add(process)
            }
            if (session.processes.isEmpty()) resume(owner)
        } catch (error: Throwable) {
            resume(owner)
            throw error
        }
    }

    @Synchronized
    fun resume(owner: IBinder) {
        val session = sessions[owner] ?: return
        var failure: Exception? = null
        val iterator = session.processes.iterator()
        while (iterator.hasNext()) {
            val process = iterator.next()
            try {
                if (sessions.values.none { it !== session && process in it.processes } &&
                    stat(process.pid)?.second == process.startTime
                ) {
                    signal(process.pid, OsConstants.SIGCONT)
                }
                iterator.remove()
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let { throw it }
        sessions.remove(owner)
        owner.unlinkToDeath(session.death, 0)
    }

    @Synchronized
    fun close() {
        closed = true
        sessions.keys.toList().forEach { runCatching { resume(it) }.withLog() }
    }

    private fun signal(pid: Int, signal: Int): Boolean = try {
        Os.kill(pid, signal)
        true
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.ESRCH) throw e
        false
    }

    private fun stat(pid: Int): Pair<Char, Long>? {
        val dir = File("/proc/$pid")
        val fields = try {
            File(dir, "stat").readText().substringAfterLast(") ").split(' ', limit = 21)
        } catch (e: FileNotFoundException) {
            if (dir.exists()) throw e
            return null
        }
        // /proc/PID/stat fields 3 (state) and 22 (start time) follow the parenthesized process name.
        return fields[0].single() to fields[19].toLong()
    }
}
