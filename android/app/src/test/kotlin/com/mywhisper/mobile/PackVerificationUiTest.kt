package com.mywhisper.mobile

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PackVerificationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onboardingAndSettingsStayResponsiveUntilVerificationCompletes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = LocalModelDownload.PackVerifyCache()
        var saves by mutableIntStateOf(0)
        try {
            compose.setContent {
                val verified = rememberVerifiedPack("model", "fp", 0, cache, { "fp" }, { true }, { it }) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    true
                }
                Text(if (verified) "READY" else "CHECKING")
                Text("Save $saves", Modifier.clickable { saves++ })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("CHECKING").assertExists()
            compose.onNodeWithText("Save 0").performClick()
            compose.onNodeWithText("Save 1").assertExists()
            release.countDown()
            compose.waitUntil(5000) { compose.onAllNodesWithText("READY").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("READY").assertExists()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun recreatedDirectoryIsWatchedAgain() {
        val root = java.nio.file.Files.createTempDirectory("watch").toFile()
        val directory = java.io.File(root, "model").apply { mkdir() }
        val listeners = mutableMapOf<String, (java.io.File) -> Unit>()
        var starts = 0
        try {
            compose.setContent {
                val generation = rememberPackChanges(root, listOf(directory)) { file, callback ->
                    if (file == directory) starts++
                    listeners[file.path] = callback
                    java.io.Closeable { listeners.remove(file.path) }
                }
                Text("Changes $generation")
            }
            compose.runOnIdle {
                assertTrue(directory.delete())
                assertTrue(directory.mkdir())
                listeners.getValue(directory.path).invoke(directory)
            }
            compose.onNodeWithText("Changes 1").assertExists()
            compose.runOnIdle {
                assertTrue(starts >= 2)
                listeners.getValue(directory.path).invoke(directory)
            }
            compose.onNodeWithText("Changes 2").assertExists()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun burstAcrossDirectoriesKeepsOtherObserverAndQueuedEvents() {
        val root = java.nio.file.Files.createTempDirectory("watch-burst").toFile()
        val first = java.io.File(root, "first").apply { mkdir() }
        val second = java.io.File(root, "second").apply { mkdir() }
        val listeners = mutableMapOf<String, (java.io.File) -> Unit>()
        val changes = mutableListOf<java.io.File>()
        var secondStarts = 0
        try {
            compose.setContent {
                rememberPackChanges(root, listOf(first, second), onChange = { changes.add(it) }) { file, callback ->
                    if (file == second) secondStarts++
                    listeners[file.path] = callback
                    java.io.Closeable { listeners.remove(file.path) }
                }
            }
            lateinit var oldFirst: (java.io.File) -> Unit
            compose.runOnIdle {
                oldFirst = listeners.getValue(first.path)
                assertTrue(first.delete())
                assertTrue(first.mkdir())
                oldFirst(first)
            }
            compose.runOnIdle {
                assertEquals(1, secondStarts)
                oldFirst(java.io.File(first, "queued"))
                listeners.getValue(second.path).invoke(java.io.File(second, "changed"))
            }
            compose.runOnIdle {
                assertTrue(changes.contains(java.io.File(first, "queued")))
                assertTrue(changes.contains(java.io.File(second, "changed")))
                assertEquals(1, secondStarts)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun selectionChangeDiscardsCancelledVerification() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = LocalModelDownload.PackVerifyCache()
        var selected by mutableStateOf("first")
        try {
            compose.setContent {
                val id = selected
                val verified = rememberVerifiedPack(id, "fp", 0, cache, { "fp" }, { selected == id }, { it }) {
                    if (id == "first") {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        true
                    } else false
                }
                Text(if (verified) "READY" else "CHECKING")
                Text("Select second", Modifier.clickable { selected = "second" })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Select second").performClick()
            release.countDown()
            compose.waitForIdle()
            compose.onNodeWithText("READY").assertDoesNotExist()
        } finally {
            release.countDown()
        }
    }
}
