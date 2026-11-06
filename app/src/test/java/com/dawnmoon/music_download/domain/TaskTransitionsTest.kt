package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TaskTransitionsTest {

    @Test
    fun `queued tasks may only resolve, pause, fail, or cancel`() {
        assertAllowedFrom(
            TaskStatus.Queued,
            TaskStatus.Resolving,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        )
    }

    @Test
    fun `resolving tasks may wait, download, pause, fail, or cancel`() {
        assertAllowedFrom(
            TaskStatus.Resolving,
            TaskStatus.Waiting,
            TaskStatus.Downloading,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        )
    }

    @Test
    fun `waiting tasks may download, pause, fail, or cancel`() {
        assertAllowedFrom(
            TaskStatus.Waiting,
            TaskStatus.Downloading,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        )
    }

    @Test
    fun `downloading tasks may complete, pause, fail, cancel, or re-resolve`() {
        assertAllowedFrom(
            TaskStatus.Downloading,
            TaskStatus.Completed,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
            TaskStatus.Resolving,
        )
    }

    @Test
    fun `paused tasks may requeue, wait, or cancel`() {
        assertAllowedFrom(
            TaskStatus.Paused,
            TaskStatus.Queued,
            TaskStatus.Waiting,
            TaskStatus.Cancelled,
        )
    }

    @Test
    fun `failed tasks may requeue or cancel`() {
        assertAllowedFrom(
            TaskStatus.Failed,
            TaskStatus.Queued,
            TaskStatus.Cancelled,
        )
    }

    @Test
    fun `completed tasks cannot transition anywhere`() {
        TaskStatus.entries.forEach { to ->
            assertFalse(
                "completed -> $to must be rejected",
                TaskTransitions.isAllowed(TaskStatus.Completed, to),
            )
        }
    }

    @Test
    fun `cancelled tasks cannot transition anywhere`() {
        TaskStatus.entries.forEach { to ->
            assertFalse(
                "cancelled -> $to must be rejected",
                TaskTransitions.isAllowed(TaskStatus.Cancelled, to),
            )
        }
    }

    @Test
    fun `a task never transitions to its own status`() {
        TaskStatus.entries.forEach { status ->
            assertFalse(
                "$status -> $status must be rejected",
                TaskTransitions.isAllowed(status, status),
            )
        }
    }

    /** Asserts the complete set of destinations reachable from [from]. */
    private fun assertAllowedFrom(from: TaskStatus, vararg expected: TaskStatus) {
        val actual = TaskStatus.entries
            .filter { to -> TaskTransitions.isAllowed(from, to) }
            .toSet()
        assertEquals("allowed transitions from $from", expected.toSet(), actual)
    }
}
