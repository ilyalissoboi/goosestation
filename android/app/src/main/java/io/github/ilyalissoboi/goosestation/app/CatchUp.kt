// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pulls until the data layout stops growing. A pull can bring in settings that name more of the data folder: a
 * settings.ini that moves a folder, whose game settings, pulled next, name memory cards of their own.
 */
object CatchUp {
    /** Settings that keep naming new paths stop here, rather than pull for ever. */
    const val MAX_PULLS = 4

    /**
     * Runs [pull], which returns whether it grew the layout, until one doesn't or [MAX_PULLS] have run, and returns
     * how many ran. Stopping at the cap with the layout still growing is reported to [log].
     */
    suspend fun run(
        log: (String) -> Unit = { Log.w(TAG, it) },
        pull: suspend () -> Boolean,
    ): Int {
        for (pulls in 1..MAX_PULLS) {
            if (!pull()) return pulls
        }
        log("The data layout kept growing; stopping after $MAX_PULLS pulls.")
        return MAX_PULLS
    }

    private const val TAG = "GooseStation"
}

/**
 * Runs one catch-up at a time ([CatchUp.run]), and says whether a game may start: not while the layout has grown and
 * its catch-up isn't done, as the game's own settings may not be in yet. A request while one runs has it pull again
 * once it's done instead. Growth is flagged ([layoutGrew]) only by pulls and pushes from outside a run, which then
 * ask for a catch-up however they end ([guardGrowth]); a run's own rounds refuse starts while it lasts anyway. Main
 * thread only, but [layoutGrew], which any thread may call.
 */
class CatchUpRunner(
    private val log: (String) -> Unit,
) {
    // The layout grew, and no round has started since: set as soon as it grows, before the caller asks for a run.
    @Volatile private var growthPending = false
    private var running = false
    private var again = false

    /** No game starts while this is false. */
    val startsAllowed get() = !growthPending && !running

    /**
     * Whether [message], the line on screen, stays as a game stops or a catch-up ends: only while starts are refused,
     * and only if it's the [refusal] line a refused start shows, which says why.
     */
    fun keepsLine(
        message: String?,
        refusal: String,
    ): Boolean = message == refusal && !startsAllowed

    /** The catch-up running, or the last one. */
    var job: Job? = null
        private set

    /** The core's paths grew the layout: starts are refused from now on, until a catch-up round starts after it. */
    fun layoutGrew() {
        growthPending = true
    }

    /**
     * Runs [block], a pull or push from outside a catch-up, which calls the `onGrowth` it's given as soon as the
     * core's paths grow the layout ([layoutGrew]). If it did, [catchUp] asks for the catch-up once [block] is done,
     * however it ends: a ticker push cancelled as the app goes to the background would otherwise leave starts refused
     * with no catch-up to pull what grew. [catchUp] mustn't suspend, as it runs after a cancellation too.
     */
    inline fun <T> guardGrowth(
        catchUp: () -> Unit,
        block: (onGrowth: () -> Unit) -> T,
    ): T {
        var grew = false
        try {
            return block {
                grew = true
                layoutGrew()
            }
        } finally {
            if (grew) catchUp()
        }
    }

    /** Asks for a catch-up: true if the caller is to [run] it, false if the one running takes it. */
    fun request(): Boolean {
        if (running) {
            again = true
            return false
        }
        running = true
        return true
    }

    /**
     * Asks for a catch-up ([request]) and, unless one is running, runs it in [scope] ([run]), then [onEnd]. Returns
     * the catch-up's [job]: this one, or the one running, which takes the request. A catch-up that ends abnormally
     * with a request it took still to pull runs again.
     */
    fun launch(
        scope: CoroutineScope,
        onEnd: () -> Unit = {},
        pull: suspend () -> Boolean,
    ): Job? {
        if (!request()) return job
        // Atomically: a job cancelled before it starts still runs, to its first suspension, so run()'s finally lets go
        // of running.
        job =
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    run(rerun = { launch(scope, onEnd, pull) }, pull = pull)
                } finally {
                    onEnd()
                }
            }
        return job
    }

    /**
     * The catch-up [request] gave the caller: [pull] returns whether it grew the layout. However it ends (at the cap,
     * failed or cancelled), starts are allowed again, unless a pull or push grew the layout since its last round
     * started: that one asks for its own catch-up, which allows them once it's done. A growth its own rounds left
     * unpulled, at the cap, is a later pull's. One that fails or is cancelled with such a request taken, which it would
     * have pulled next, keeps starts refused and calls [rerun], not to drop it.
     */
    suspend fun run(
        rerun: () -> Unit = {},
        pull: suspend () -> Boolean,
    ) {
        try {
            do {
                again = false
                CatchUp.run(log) {
                    // This round pulls the layout as it is now, growth included.
                    growthPending = false
                    pull()
                }
            } while (again)
        } finally {
            // Only after an abnormal end: a normal one pulls every request it took.
            val owed = again
            again = false
            running = false
            if (owed) {
                growthPending = true
                rerun()
            }
        }
    }
}
