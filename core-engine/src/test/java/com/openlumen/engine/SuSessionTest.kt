package com.openlumen.engine

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The session protocol against a real shell. `sh` stands in for `su`: what is
 * under test is the framing -- subshell, exit code, output, standard input --
 * not root.
 */
class SuSessionTest {
    private lateinit var shell: RootShell

    @Before fun startShell() {
        assumeTrue(File("/bin/sh").canExecute())
        shell = requireNotNull(RootShell.start(listOf("/bin/sh")))
    }

    @After fun closeShell() {
        if (::shell.isInitialized) shell.close()
    }

    @Test fun `a command returns its output and exit code`() {
        val result = shell.run("echo hello", timeoutMs = 2_000)
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).isEqualTo("hello")
    }

    @Test fun `exit and set -e end only that command`() {
        assertThat(shell.run("exit 3", timeoutMs = 2_000).exitCode).isEqualTo(3)
        val failed = shell.run("set -e\nfalse\necho unreachable", timeoutMs = 2_000)
        assertThat(failed.exitCode).isEqualTo(1)
        assertThat(failed.stdout).isEmpty()
        assertThat(shell.run("echo still here", timeoutMs = 2_000).stdout).isEqualTo("still here")
    }

    @Test fun `output without a final newline is kept`() {
        assertThat(shell.run("printf abc", timeoutMs = 2_000).stdout).isEqualTo("abc")
    }

    @Test fun `stderr comes back with stdout`() {
        assertThat(shell.run("echo oops >&2", timeoutMs = 2_000).stdout).isEqualTo("oops")
    }

    @Test fun `a command cannot read the commands queued behind it`() {
        // cat would otherwise swallow the marker line and hang the session.
        val result = shell.run("cat", timeoutMs = 2_000)
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(shell.usable).isTrue()
    }

    @Test fun `a command that times out ends the session`() {
        val result = shell.run("sleep 5", timeoutMs = 200)
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(shell.usable).isFalse()
    }

    @Test fun `many commands share one shell`() {
        repeat(50) { i ->
            assertThat(shell.run("echo $i", timeoutMs = 2_000).stdout).isEqualTo("$i")
        }
        assertThat(shell.run("echo \$\$", timeoutMs = 2_000).stdout)
            .isEqualTo(shell.run("echo \$\$", timeoutMs = 2_000).stdout)
    }
}
