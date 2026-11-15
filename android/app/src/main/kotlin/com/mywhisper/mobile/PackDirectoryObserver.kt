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

private fun observeDirectory(directory: File, onChange: (File) -> Unit): Closeable {
    val handler = Handler(Looper.getMainLooper())
    var active = true
    val observer = object : FileObserver(directory, ATTRIB or CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or CREATE or DELETE_SELF or MOVE_SELF) {
        override fun onEvent(event: Int, path: String?) {
            val changed = if (path == null) directory else File(directory, path)
            handler.post { if (active) onChange(changed) }
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
    onChange: (File) -> Unit = {},
    observe: (File, (File) -> Unit) -> Closeable = ::observeDirectory,
): Int {
    var generation by remember { mutableIntStateOf(0) }
    val watched = directories.flatMap { directory ->
        generateSequence(directory) { it.parentFile }.takeWhile { it.path.startsWith(root.path) }.toList()
    }.filter { it.isDirectory }.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
    DisposableEffect(watched, generation) {
        val observers = watched.map { directory -> observe(directory) { changed -> onChange(changed); generation++ } }
        onDispose { observers.forEach { it.close() } }
    }
    return generation
}

/** True for the input itself or an ancestor/descendant filesystem change. */
internal fun affectsPackPath(changed: File, input: File): Boolean {
    val changedPath = changed.toPath().toAbsolutePath().normalize()
    val inputPath = input.toPath().toAbsolutePath().normalize()
    return changedPath.startsWith(inputPath) || inputPath.startsWith(changedPath)
}
