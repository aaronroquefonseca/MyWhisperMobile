package com.mywhisper.mobile

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.Closeable
import java.io.File

private fun observeDirectory(directory: File, onChange: (File) -> Unit): Closeable {
    val handler = Handler(Looper.getMainLooper())
    val mask = FileObserver.ATTRIB or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
        FileObserver.DELETE or FileObserver.CREATE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    val observer = object : FileObserver(directory, mask) {
        override fun onEvent(event: Int, path: String?) {
            // IN_IGNORED (sent on stopWatching) and other unrequested bits arrive regardless of the mask.
            if ((event and mask) == 0) return
            val changed = if (path == null) directory else File(directory, path)
            handler.post { onChange(changed) }
        }
    }
    observer.startWatching()
    return Closeable { observer.stopWatching() }
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
    val lifetime = remember { booleanArrayOf(true) }
    val attached = remember { mutableSetOf<String>() }
    val epochs = remember { mutableMapOf<String, Int>() }
    DisposableEffect(Unit) {
        lifetime[0] = true
        onDispose { lifetime[0] = false }
    }
    val watched = directories.flatMap { directory ->
        generateSequence(directory) { it.parentFile }.takeWhile { it.path.startsWith(root.path) }.toList()
    }.filter { it.isDirectory }.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
    for (directory in watched) {
        key(directory.absolutePath) {
            // Inode + self-event epoch, never timestamps: Android has no birthtime, so
            // creationTime() is the dir mtime, which every child write (state.db-wal in
            // filesDir) bumps → reattach → reconcile → all packs re-verified in a loop.
            val fileKey = try {
                java.nio.file.Files.readAttributes(
                    directory.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey()
            } catch (_: Exception) { null }
            DisposableEffect(fileKey, epochs[directory.absolutePath]) {
                val observer = observe(directory) { changed ->
                    if (lifetime[0]) {
                        // Event on the directory itself (deleted/moved): the watch may be dead, reattach.
                        if (changed == directory) epochs[directory.absolutePath] = (epochs[directory.absolutePath] ?: 0) + 1
                        onChange(changed); generation++
                    }
                }
                // Reconcile the directory after reattachment, covering the small watch gap.
                if (!attached.add(directory.absolutePath)) onChange(directory)
                onDispose { observer.close() }
            }
        }
    }
    return generation
}

/** True for the input itself or an ancestor/descendant filesystem change. */
internal fun affectsPackPath(changed: File, input: File): Boolean {
    val changedPath = changed.toPath().toAbsolutePath().normalize()
    val inputPath = input.toPath().toAbsolutePath().normalize()
    return changedPath.startsWith(inputPath) || inputPath.startsWith(changedPath)
}
