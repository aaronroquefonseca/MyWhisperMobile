package com.mywhisper.mobile

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.Closeable
import java.io.File

private fun observeDirectory(directory: File, onChange: () -> Unit): Closeable {
    val handler = Handler(Looper.getMainLooper())
    var active = true
    val observer = object : FileObserver(directory, ATTRIB or CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or CREATE or DELETE_SELF or MOVE_SELF) {
        override fun onEvent(event: Int, path: String?) {
            handler.post { if (active) onChange() }
        }
    }
    observer.startWatching()
    return Closeable { active = false; observer.stopWatching() }
}

/** Watch existing ancestors too, so newly created model directories become observable. */
@Composable
internal fun rememberPackChanges(
    root: File,
    directories: List<File>,
    onChange: () -> Unit = {},
    observe: (File, () -> Unit) -> Closeable = ::observeDirectory,
): Int {
    var generation by remember { mutableIntStateOf(0) }
    val watched = directories.flatMap { directory ->
        generateSequence(directory) { it.parentFile }.takeWhile { it.path.startsWith(root.path) }.toList()
    }.filter { it.isDirectory }.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
    DisposableEffect(watched, generation) {
        val observers = watched.map { directory -> observe(directory) { onChange(); generation++ } }
        onDispose { observers.forEach { it.close() } }
    }
    return generation
}
