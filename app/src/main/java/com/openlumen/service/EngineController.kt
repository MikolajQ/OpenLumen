package com.openlumen.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.openlumen.diagnostics.DiagnosticsLog
import com.openlumen.engine.ColorEngine
import com.openlumen.engine.DisplayEmergencyReset
import com.openlumen.engine.DriverProbe
import com.openlumen.engine.EngineResult
import com.openlumen.engine.EngineKind
import com.openlumen.engine.LumenMatrix
import com.openlumen.engine.Presets
import com.openlumen.engine.Su
import com.openlumen.engine.engines.OverlayEngine
import com.openlumen.engine.withGrayscale
import com.openlumen.prefs.DirectBootState
import com.openlumen.prefs.EngineKindDto
import com.openlumen.prefs.Preferences
import com.openlumen.prefs.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Owns active engine selection, serialized apply/clear calls, and ramp jobs.
 */
internal class EngineController(
    private val context: Context,
    private val probe: DriverProbe,
    private val prefs: PreferencesStore,
    private val scope: CoroutineScope,
    private val isUserUnlocked: () -> Boolean,
    private val logTag: String,
    /**
     * Called when the secure-settings driver lets go of a row because the user
     * or another app changed it (C346). Defaulted so the existing tests, which
     * exercise the engine ladder rather than the watch, construct unchanged.
     */
    private val onSecureRowsReleased: suspend (Map<String, String>) -> Unit = {},
    /**
     * True while the system shows the screen in gray by itself (LineageOS
     * reading mode, or grayscale colour correction). The SurfaceFlinger
     * driver replaces the matrix that gray lives in, so it has to carry the
     * gray itself (#32).
     */
    private val systemGrayscale: () -> Boolean = { false }
) {
    @Volatile private var engine: ColorEngine? = null
    @Volatile private var transitionJob: Job? = null
    private val applyMutex = Mutex()
    private val rampMutex = Mutex()
    @Volatile private var lastApplied: LumenMatrix? = null

    /**
     * What the display shows while nothing has been applied in this process
     * yet, when that is known: identity after this controller cleared an
     * engine, or when the service learned at start that the filter was off.
     * It is what a fade in starts from. Null means unknown -- a restart with a
     * transform still on the panel -- and the first apply then lands at once,
     * as before, rather than flashing back to identity to fade from there.
     */
    @Volatile private var assumedOutput: LumenMatrix? = null

    /** Where the running fade is heading, for [finishTransitionNow]. */
    @Volatile private var transitionTarget: Pair<Boolean, LumenMatrix>? = null
    private val powerManager: PowerManager? =
        runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
    private val applyGate = ApplyDecisionGate()
    /**
     * Auto's last probe. The probe is what is expensive (`su` for the root
     * drivers), and the choice it feeds also depends on the matrices the
     * settings ask for (C364), so the probe is kept and the choice is made
     * again on every resolve. Cleared wherever the old kind cache was.
     */
    @Volatile private var cachedAutoProbes: List<DriverProbe.Probe>? = null
    @Volatile private var lastEngineSelection: EngineKindDto? = null

    /**
     * Whether the overlay was available in the probe Auto last chose from,
     * kept even when that choice was "nothing" and the filter sat in standby,
     * which is a fresh install before any permission (C364). Null until Auto
     * has resolved once. [refreshOverlayChoice] compares against it.
     */
    @Volatile private var lastAutoOverlayAnswer: Boolean? = null

    /**
     * One resolve at a time. The preference collector ([ensureEngineFor]) and
     * the app's overlay check ([refreshOverlayChoice]) each resolve in their
     * own coroutine, and a resolve
     * suspends in the probe: an older answer landing second would switch
     * back to the driver the other call just left.
     */
    private val ensureMutex = Mutex()

    /**
     * Bumped when every [hardClearOutputs] starts and again when it ends. A
     * resolve suspends in the probe, so a turn-off can clear everything while
     * one is in flight; the resolve then sees the generation moved and
     * installs nothing, rather than putting a driver back on a filter that
     * was just switched off. The second bump covers a resolve that began
     * while the clear was still running.
     */
    @Volatile private var clearGeneration = 0
    private val secureWatch = SecureSettingsWatch(context, scope, onSecureRowsReleased)

    /**
     * Set when a turn-off left the system's own gray on screen rather than
     * identity (#32), so the shutdown sweep that follows does not wipe it.
     */
    @Volatile private var systemGrayHandedBack = false

    /**
     * True once the blind root sweep has run with nothing applied since (C325).
     *
     * One turn-off reaches that sweep three times: the command runs it, the
     * `enabled = false` the command writes lands on the preference collector
     * and runs it again through `clearAndStop`, and `stopSelf` runs it a third
     * time on the way down. Every pass issues the same SurfaceFlinger disable
     * transaction and the same KCAL probes, and each one spawns `su` -- a stall
     * the user sees, and on some managers a prompt. The repeats cannot find
     * anything the first pass left behind, because nothing applies a transform
     * in between.
     *
     * Deliberately scoped to this controller instance. A fresh process starts
     * with it false, so the recovery case the sweep exists for -- a tinted
     * display that no live engine has a handle on -- always sweeps. Any apply
     * puts a transform back and arms it again.
     */
    @Volatile private var rootTransformsSwept = false

    suspend fun ensureEngineFor(prefs: Preferences) = ensureMutex.withLock { ensureEngineForLocked(prefs) }

    /**
     * Look again at Auto's choice after "display over other apps" may have
     * been granted or taken away (C364). Auto picks between System Night Light
     * and the overlay on the overlay's answer, and a grant changes no setting,
     * so nothing else would make it look.
     *
     * Only the overlay is read here. The full probe runs `su` for the root
     * drivers, so a resolve follows only when the overlay's answer differs
     * from the one Auto last chose with, and before Auto has chosen at all
     * this does nothing. Returns true when the resolve changed the driver,
     * and false when a turn-off cleared the outputs meanwhile, since then the
     * change was the clear's and there is nothing to apply.
     */
    suspend fun refreshOverlayChoice(prefs: Preferences): Boolean = ensureMutex.withLock {
        if (prefs.engine != EngineKindDto.Auto || lastEngineSelection != EngineKindDto.Auto) {
            return@withLock false
        }
        val last = lastAutoOverlayAnswer ?: return@withLock false
        val overlay = probe.engineOf(EngineKind.OVERLAY) ?: return@withLock false
        val now = runCatching { overlay.isAvailable(context) }.getOrDefault(false)
        if (now == last) return@withLock false
        val before = engine?.kind
        val generation = clearGeneration
        ensureEngineForLocked(prefs)
        clearGeneration == generation && engine?.kind != before
    }

    private fun overlayAvailable(probes: List<DriverProbe.Probe>): Boolean =
        probes.any { it.engine.kind == EngineKind.OVERLAY && it.available }

    private suspend fun ensureEngineForLocked(prefs: Preferences) {
        val generation = clearGeneration
        if (prefs.engine != lastEngineSelection) {
            cachedAutoProbes = null
            lastEngineSelection = prefs.engine
        }
        val want = resolveDesiredEngineKind(prefs)
        val current = engine
        if (want == null) {
            if (current != null) {
                cancelTransition()
                applyMutex.withLock {
                    escalateClearFailure(
                        "engine.clear() while no driver is available",
                        current.kind,
                        runCatching {
                            current.clear(context)
                        }.getOrElse { EngineResult.Failure(it.message ?: "exception") }
                    )
                    engine = null
                    lastApplied = null
                    assumedOutput = LumenMatrix.IDENTITY
                    applyGate.reset()
                }
            }
            cachedAutoProbes = null
            Log.w(logTag, "no available display driver; filter is in standby")
            return
        }
        if (current?.kind == want) return
        cancelTransition()
        applyMutex.withLock {
            if (clearGeneration != generation) {
                Log.i(logTag, "outputs were cleared during the resolve; not installing $want")
                return@withLock
            }
            // The outgoing engine is about to be cleared, so whatever it owned
            // it no longer owns. Waiting for the next apply to notice would
            // leave an observer registered against a released row.
            secureWatch.stop()
            current?.let {
                escalateClearFailure(
                    "engine.clear() during switch",
                    it.kind,
                    runCatching {
                        it.clear(context)
                    }.getOrElse { error -> EngineResult.Failure(error.message ?: "exception") }
                )
            }
            val next = probe.engineOf(want)
            if (next == null) {
                Log.e(logTag, "DriverProbe returned null for $want - staying on previous engine")
                return@withLock
            }
            if (next is OverlayEngine && !next.installView(context, Presets.OFF)) {
                Log.w(logTag, "overlay driver could not install its host view")
                engine = null
                lastApplied = null
                applyGate.reset()
                cachedAutoProbes = null
                return@withLock
            }
            engine = next
            lastApplied = null
            // The previous driver has just been cleared, so the new one fades
            // in from an unfiltered screen.
            if (current != null) assumedOutput = LumenMatrix.IDENTITY
            applyGate.reset()
            DiagnosticsLog.log(
                context,
                DiagnosticsLog.Level.INFO,
                DiagnosticsLog.Category.ENGINE,
                "switched to engine ${next.kind.name}"
            )
        }
    }

    /**
     * [ramp] asks for the transition fade even when the filter stays on, for
     * the day filter handing over to the evening preset and back, or a new
     * preset picked: to the gate that is one active target replacing another,
     * which a slider drag also is, and a slider drag must not fade.
     */
    suspend fun applyIfNeeded(
        shouldBeActive: Boolean,
        matrix: LumenMatrix,
        transitionDurationMs: Long,
        ramp: Boolean = false
    ) {
        applyGate.next(shouldBeActive, matrix)?.let { decision ->
            val rampMs = if (decision.isStateFlip || ramp) transitionDurationMs.coerceAtLeast(0L) else 0L
            applyMatrix(decision, shouldBeActive, rampMs)
        }
    }

    /** The driver currently applying, or null before the first engine is chosen. */
    fun activeEngineKind(): EngineKind? = engine?.kind

    /**
     * The service read at start that the filter was off, so the display shows
     * no transform and the first apply can fade in from identity. Ignored once
     * something has been applied.
     */
    fun assumeIdentityOnScreen() {
        if (lastApplied == null) assumedOutput = LumenMatrix.IDENTITY
    }

    /**
     * Fade what is on screen to identity, for a turn-off, and return once it
     * is there or the fade was cancelled. The caller clears the engine after
     * this as before; the fade only decides how the screen gets there.
     *
     * Runs as the transition job rather than under the ramp lock, so an
     * emergency turn-off arriving in the middle still cancels it at once.
     * The gate is reset first: from the first frame the screen no longer shows
     * the committed target, and a turn-on arriving mid-fade must apply again
     * rather than be dropped as a duplicate.
     *
     * One turn-off can ask twice -- the notification's Off fades, and the
     * `enabled = false` it writes fades again on the preference collector --
     * so a second caller waits for the fade already running instead of
     * restarting it, which would let the first caller's clear cut it short.
     */
    suspend fun fadeToIdentity(durationMs: Long) {
        val job = rampMutex.withLock {
            val running = transitionJob
            if (running?.isActive == true && transitionTarget == FADE_OUT_TARGET) return@withLock running
            cancelTransitionLocked()
            val from = lastApplied ?: return
            if (durationMs <= 0L || from == LumenMatrix.IDENTITY || engine == null || !screenInteractive()) {
                return
            }
            applyGate.reset()
            transitionTarget = FADE_OUT_TARGET
            scope.launch {
                try {
                    runFade(from, LumenMatrix.IDENTITY, durationMs) {}
                } catch (_: CancellationException) {
                    // A turn-on, the hard clear or the screen going off won.
                } catch (t: Throwable) {
                    Log.w(logTag, "fade out aborted: ${t.message}")
                }
            }.also { transitionJob = it }
        }
        job.join()
    }

    /**
     * Land a running fade on its target now. The screen went off: nobody sees
     * the rest of it, and a fade that kept going would freeze when the device
     * sleeps and resume, part way, when the screen comes back.
     */
    suspend fun finishTransitionNow() {
        rampMutex.withLock {
            if (transitionJob?.isActive != true) return
            val (shouldBeActive, target) = transitionTarget ?: return
            cancelTransitionLocked()
            if (applyOnce(target)) applyGate.commit(shouldBeActive, target) else applyGate.reset()
        }
    }

    private fun screenInteractive(): Boolean =
        runCatching { powerManager?.isInteractive }.getOrNull() ?: true

    /**
     * Put the committed target back after the system has written its own
     * colour matrix over it (see [SystemColorConflict]).
     *
     * [applyIfNeeded] cannot do this: the gate sees the same target it already
     * committed and drops it as a duplicate. Only the SurfaceFlinger driver is
     * overwritten this way, and only an active filter is restored; with the
     * filter off the system's Night Light is what the user asked for. The jump
     * is immediate because the screen is already showing the wrong colour.
     */
    suspend fun reassertAfterSystemOverwrite(): Boolean {
        if (engine?.kind != EngineKind.SURFACE_FLINGER) return false
        val target = applyGate.committedActiveTarget() ?: return false
        return rampMutex.withLock {
            cancelTransitionLocked()
            val ok = applyOnce(target)
            if (ok) applyGate.commit(shouldBeActive = true, matrix = target) else applyGate.reset()
            ok
        }
    }

    /**
     * Drop every output this process can reach.
     *
     * [blunt] decides whether the secure-settings rows are switched off without
     * regard to who owns them (C291). They are persistent system settings the
     * user may have had on before OpenLumen ever ran, and
     * `SecureSettingsEngine.clearKnownSecureState` cannot tell the difference,
     * so only the emergency paths may use it: an explicit turn-off command, or
     * a `clear()` that failed and left a transform on the display with nothing
     * holding a handle to it. An ordinary disable runs the engine's own
     * `clear()` just above, which restores the user's values, and a blunt pass
     * afterwards would zero exactly what that restore put back.
     */
    suspend fun hardClearOutputs(reason: String, blunt: Boolean = false) {
        cancelTransition()
        applyMutex.withLock {
            clearGeneration++
            val clearedEngine = engine
            engine?.let {
                reportResult("engine.clear() during hard clear", runCatching {
                    it.clear(context)
                }.getOrElse { error -> EngineResult.Failure(error.message ?: "exception") })
            }
            runCatching { (probe.engineOf(EngineKind.OVERLAY) as? OverlayEngine)?.clear(context) }
                .onFailure { Log.w(logTag, "overlay hard clear failed: ${it.message}") }
            val sweepRoots = !rootTransformsSwept
            runCatching {
                DisplayEmergencyReset.clearRootTransforms(
                    context = context.takeIf { blunt },
                    roots = sweepRoots
                )
            }
                .onSuccess { result ->
                    // Latch only on a sweep that actually reached the panel.
                    // Both halves return an empty list without doing anything
                    // when `su` is unavailable or was denied, and that is the
                    // case that most needs the next pass to try again: a user
                    // who answers the Magisk prompt a second late would
                    // otherwise be left with the tint and no retry until the
                    // process is replaced. On a rootless device nothing is
                    // latched and nothing is spent, because both halves
                    // short-circuit on the cached availability probe.
                    //
                    // Only the root half is latched. The secure half still runs
                    // on every blunt pass: it is a different family, it costs no
                    // su, and an ordinary disable never touches it (C291).
                    if (result.surfaceFlingerCodes.isNotEmpty() || result.kcalPaths.isNotEmpty()) {
                        rootTransformsSwept = true
                    }
                    DiagnosticsLog.log(
                        context,
                        DiagnosticsLog.Level.INFO,
                        DiagnosticsLog.Category.ENGINE,
                        "$reason: hard reset secure=${result.secureSettingsKeys.joinToString().ifBlank { "none" }} " +
                            "SF=${result.surfaceFlingerCodes.rootField(sweepRoots)} " +
                            "KCAL=${result.kcalPaths.rootField(sweepRoots)}"
                    )
                }
                .onFailure { Log.w(logTag, "root hard clear failed: ${it.message}") }
            // An emergency reset clears everything on purpose. An ordinary
            // turn-off only takes the filter away and gives back what the
            // system was showing underneath it.
            if (!blunt) handBackSystemGrayscale(clearedEngine)
            secureWatch.stop()
            engine = null
            lastApplied = null
            assumedOutput = LumenMatrix.IDENTITY
            applyGate.reset()
            cachedAutoProbes = null
            // Again at the end: a resolve that read the generation while this
            // clear was still running would otherwise match it and install.
            clearGeneration++
        }
    }

    suspend fun restoreDirectBootState(state: DirectBootState) {
        val selected = directBootEngineFor(state.engine)
        val matrix = state.toLumenMatrix()
        applyMutex.withLock {
            if (selected is OverlayEngine && !selected.installView(context, Presets.OFF)) {
                engine = null
                lastApplied = null
                applyGate.reset()
                Log.w(logTag, "direct-boot overlay install failed")
                return@withLock
            }
            engine = selected
            lastApplied = null
            applyGate.reset()
            rootTransformsSwept = false
            val result = runCatching { selected.apply(context, matrix) }
                .getOrElse { EngineResult.Failure(it.message ?: "exception") }
            when (result) {
                EngineResult.Success -> {
                    lastApplied = matrix
                    applyGate.commit(state.active, matrix)
                    DiagnosticsLog.log(
                        context,
                        DiagnosticsLog.Level.INFO,
                        DiagnosticsLog.Category.ENGINE,
                        "direct-boot restore on ${selected.kind.name}"
                    )
                }
                is EngineResult.Failure -> {
                    Log.w(logTag, "direct-boot apply failed: ${result.message}")
                    cachedAutoProbes = null
                }
            }
        }
    }

    fun cancelJobs() {
        transitionJob?.cancel()
        transitionJob = null
        secureWatch.stop()
        transitionTarget = null
    }

    /**
     * Deliberately takes no lock, unlike every other engine call here.
     *
     * `onDestroy` calls this from `runBlocking` on the main thread, which
     * parks the Looper. Every holder of [applyMutex] is a coroutine on
     * `Dispatchers.Main.immediate` suspended inside the engine's own
     * `withContext(Dispatchers.IO)`, and it can only resume by posting back to
     * that parked Looper. Waiting for the lock therefore waits for something
     * that cannot happen: the two-second cap in `onDestroy` expires and the
     * clear never runs at all, which loses the active engine's own restore.
     * An interleaved clear is a narrow window; not clearing is certain. The
     * KCAL record protects itself instead (C326).
     */
    suspend fun clearActiveEngineForShutdown() {
        val current = engine ?: return
        secureWatch.stop()
        if (handBackSystemGrayscale(current)) return
        runCatching { current.clear(context) }
    }

    /**
     * Leave the system's own gray on screen when the SurfaceFlinger driver lets
     * go (#32).
     *
     * Clearing the driver's matrix sends identity, and the system does not
     * re-send the reading-mode or grayscale matrix it had there until that
     * setting next changes, so turning the filter off on a gray screen used to
     * bring the colour back. Writing the bare gray instead leaves the screen
     * the way the system has it; when the user later switches the gray off,
     * the system writes identity over this itself.
     */
    private suspend fun handBackSystemGrayscale(current: ColorEngine?): Boolean {
        if (current?.kind != EngineKind.SURFACE_FLINGER || !systemGrayscale()) return false
        val ok = runCatching { current.apply(context, LumenMatrix.IDENTITY.withGrayscale(1f)) }
            .getOrNull() == EngineResult.Success
        systemGrayHandedBack = ok
        if (ok) {
            DiagnosticsLog.log(
                context,
                DiagnosticsLog.Level.INFO,
                DiagnosticsLog.Category.ENGINE,
                "turn-off: left the system's grayscale on the SurfaceFlinger matrix"
            )
        }
        return ok
    }

    /**
     * Shutdown is not an emergency: [clearActiveEngineForShutdown] has already
     * given the active engine a chance to restore what it owns, so the secure
     * rows are deliberately left out of this pass (C291).
     */
    suspend fun clearRootTransformsForShutdown() {
        // stopSelf() follows a hard clear by milliseconds on the ordinary
        // paths, so this is usually the third identical sweep of one turn-off
        // (C325). It still runs when the service is stopped some other way.
        if (rootTransformsSwept || systemGrayHandedBack) return
        runCatching { DisplayEmergencyReset.clearRootTransforms(context = null) }
            .onSuccess { result ->
                if (result.surfaceFlingerCodes.isNotEmpty() || result.kcalPaths.isNotEmpty()) {
                    rootTransformsSwept = true
                }
                DiagnosticsLog.log(
                    context,
                    DiagnosticsLog.Level.INFO,
                    DiagnosticsLog.Category.ENGINE,
                    "shutdown: hard reset secure=none " +
                        "SF=${result.surfaceFlingerCodes.joinToString().ifBlank { "none" }} " +
                        "KCAL=${result.kcalPaths.joinToString().ifBlank { "none" }}"
                )
            }
            .onFailure { Log.w(logTag, "shutdown hard clear failed: ${it.message}") }
    }

    private suspend fun resolveDesiredEngineKind(prefsSnapshot: Preferences): EngineKind? {
        if (prefsSnapshot.engine == EngineKindDto.Auto) return resolveAutoEngineKind(prefsSnapshot)

        val requested = prefsSnapshot.engine.toEngineKind()
        val requestedAvailable = (
            probe.engineOf(requested)
                ?.let { engine -> runCatching { engine.isAvailable(context) }.getOrDefault(false) }
            ) == true
        if (
            honourPinnedEngine(
                forcePinned = prefsSnapshot.forcePinnedEngine,
                probeSaysAvailable = requestedAvailable
            )
        ) {
            if (!requestedAvailable) {
                val forced = "selected engine ${requested.name} probed unavailable; " +
                    "using it anyway because the driver is force-pinned"
                Log.w(logTag, forced)
                DiagnosticsLog.log(
                    context,
                    DiagnosticsLog.Level.WARN,
                    DiagnosticsLog.Category.ENGINE,
                    forced
                )
            }
            return requested
        }

       val fallback = resolveAutoEngineKind(prefsSnapshot)
        if (fallback == null) {
            val message = "selected engine ${requested.name} unavailable and no fallback is available"
            Log.w(logTag, message)
            DiagnosticsLog.log(
                context,
                DiagnosticsLog.Level.WARN,
                DiagnosticsLog.Category.ENGINE,
                message
            )
            if (isUserUnlocked()) {
                prefs.update { current ->
                    if (current.engine == prefsSnapshot.engine) current.copy(engine = EngineKindDto.Auto) else current
                }
            }
            return null
        }
       val message = "selected engine ${requested.name} unavailable; using Auto (${fallback.name})"
       Log.w(logTag, message)
        DiagnosticsLog.log(
            context,
            DiagnosticsLog.Level.WARN,
            DiagnosticsLog.Category.ENGINE,
            message
        )
        if (isUserUnlocked()) {
            prefs.update { current ->
                if (current.engine == prefsSnapshot.engine) current.copy(engine = EngineKindDto.Auto) else current
            }
        }
        return fallback
    }

    private suspend fun resolveAutoEngineKind(prefsSnapshot: Preferences): EngineKind? {
        val probes = cachedAutoProbes?.let { probe.withFreshOverlay(context, it) } ?: probe.probeAll(context)
        lastAutoOverlayAnswer = overlayAvailable(probes)
        return DriverProbe.bestAvailableKind(probes, driverTargets(prefsSnapshot))
            ?.also { cachedAutoProbes = probes }
    }

    private fun EngineKindDto.toEngineKind(): EngineKind = when (this) {
        EngineKindDto.Auto -> EngineKind.OVERLAY
        EngineKindDto.ColorDisplayManager -> EngineKind.COLOR_DISPLAY_MANAGER
        EngineKindDto.SurfaceFlinger -> EngineKind.SURFACE_FLINGER
        EngineKindDto.Kcal -> EngineKind.KCAL
        EngineKindDto.Overlay -> EngineKind.OVERLAY
    }

    private suspend fun applyMatrix(
        decision: ApplyDecision,
        shouldBeActive: Boolean,
        durationMs: Long
    ) {
        rampMutex.withLock {
            cancelTransitionLocked()

            val previous = lastApplied ?: assumedOutput
            // A fade nobody can see is only cost: land the target at once.
            val fadeMs = if (screenInteractive()) durationMs else 0L
            if (fadeMs <= 0 || previous == null || previous == decision.matrix) {
                if (applyOnce(decision.matrix)) {
                    applyGate.commit(shouldBeActive, decision.matrix)
                } else {
                    applyGate.reset()
                }
            } else {
                transitionTarget = shouldBeActive to decision.matrix
                transitionJob = scope.launch {
                    var committed = false
                    var completed = false
                    try {
                        completed = runFade(previous, decision.matrix, fadeMs) {
                            if (!committed) {
                                applyGate.commit(shouldBeActive, decision.matrix)
                                committed = true
                            }
                        }
                        if (!completed) applyGate.reset()
                    } catch (_: CancellationException) {
                        // Expected when a newer target, engine switch, or manual off wins.
                    } catch (t: Throwable) {
                        Log.w(logTag, "transition ramp aborted: ${t.message}")
                    }
                    if (!completed && committed) {
                        applyGate.reset()
                    }
                }
            }
        }
    }

    /**
     * Step the display from [from] to [to] over [durationMs]; false when a
     * frame was refused.
     *
     * The number of frames follows the size of the change, about 1% of full
     * scale per frame ([planFade]), and never comes closer together than the
     * driver allows, so a one-second fade is about twenty frames and a
     * five-minute one about a hundred, not three hundred. Progress follows
     * the clock, not the frame count: a frame that ran late is not replayed,
     * the next one lands where the fade should be by then. The curve eases in
     * and out, which reads as one movement instead of a start and a stop.
     *
     * All frames share one root shell ([Su.withSession]): starting `su` cost
     * as much as the frame itself.
     */
    private suspend fun runFade(
        from: LumenMatrix,
        to: LumenMatrix,
        durationMs: Long,
        onFrame: () -> Unit
    ): Boolean {
        val plan = planFade(from, to, durationMs, engine?.minFadeFrameMs ?: ColorEngine.DEFAULT_MIN_FADE_FRAME_MS)
        // A change too small to step through is not worth waiting a frame for.
        if (plan.frames <= 1) return applyOnce(to).also { if (it) onFrame() }
        return Su.withSession {
            val startNs = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000L
            var frame = 0
            var outcome: Boolean? = null
            while (outcome == null) {
                frame++
                val dueMs = frame * plan.frameMs
                val waitMs = dueMs - elapsedMs()
                if (waitMs > 0) delay(waitMs)
                val elapsed = elapsedMs()
                if (elapsed > dueMs) frame = (elapsed / plan.frameMs).toInt().coerceAtLeast(frame)
                val t = if (frame >= plan.frames) 1f else (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                outcome = when {
                    !applyOnce(from.lerp(to, easeInOut(t))) -> false
                    else -> {
                        onFrame()
                        if (t >= 1f) true else null
                    }
                }
            }
            outcome == true
        }
    }

    private suspend fun cancelTransition() {
        rampMutex.withLock { cancelTransitionLocked() }
    }

    private suspend fun cancelTransitionLocked() {
        val prior = transitionJob ?: return
        prior.cancel()
        try {
            prior.join()
        } catch (_: Throwable) {
            // CancellationException expected.
        }
        transitionJob = null
        transitionTarget = null
    }

    private suspend fun applyOnce(matrix: LumenMatrix): Boolean {
        return applyMutex.withLock {
           val current = engine
           if (current == null) {
               Log.w(logTag, "applyOnce: no engine yet, skipping")
                return@withLock false
           } else {
                // Before the attempt, not after a success. A driver that
                // reports failure may still have reached the panel: a `su`
                // that times out mid-transaction, or a `set -e` script whose
                // later write failed after the earlier one landed. Both are
                // documented here already (C256, C257), and both leave a
                // transform the blunt sweep is the only thing that can clear.
                rootTransformsSwept = false
                systemGrayHandedBack = false
                // The gate, the ramp and lastApplied all deal in the target
                // the user chose. Only what reaches the driver carries the
                // system's gray, so a change to that setting can be picked up
                // by re-applying the same target.
                val output = if (current.kind == EngineKind.SURFACE_FLINGER && systemGrayscale()) {
                    matrix.withGrayscale(1f)
                } else {
                    matrix
                }
                val result = runCatching { current.apply(context, output) }
                    .getOrElse { EngineResult.Failure(it.message ?: "exception") }
                when (result) {
                    EngineResult.Success -> {
                        lastApplied = matrix
                        // After the write, because which rows are owned is
                        // decided by the apply that just ran: a neutral preset
                        // hands Night Light back and a session that never dims
                        // never takes the Extra Dim rows at all.
                        secureWatch.sync(current)
                        return@withLock true
                    }
                    is EngineResult.Failure -> {
                        Log.w(logTag, "engine.apply() failed: ${result.message}")
                       cachedAutoProbes = null
                        return@withLock false
                    }
                   }
           }
       }
   }

    /**
     * A sweep that was skipped reported nothing, which is not the same as a
     * sweep that ran and found nothing. "none" for both would make the
     * diagnostics timeline claim the second reading.
     */
    private fun <T> List<T>.rootField(swept: Boolean): String =
        if (!swept) "already swept" else joinToString().ifBlank { "none" }

    private fun reportResult(operation: String, result: EngineResult) {
        if (result is EngineResult.Failure) {
            Log.w(logTag, operation + " failed: " + result.message)
        }
    }

    /**
     * C256: a clear that reports failure means a transform may still be on the
     * display with nothing left holding a handle to it. Logging that and
     * moving on is how a user ends up staring at a tint no control can remove,
     * so fall through to the same blunt reset the emergency-off path uses.
     *
     * Callers already hold [applyMutex], so this deliberately does not go
     * through [hardClearOutputs], which takes it.
     */
    private suspend fun escalateClearFailure(
        operation: String,
        kind: EngineKind,
        result: EngineResult
    ) {
        reportResult(operation, result)
        if (result !is EngineResult.Failure) return
        if (!escalatesToBluntReset(kind)) {
            Log.w(logTag, "$operation failed (${result.message}); nothing blunt to escalate to")
            return
        }
        DiagnosticsLog.log(
            context,
            DiagnosticsLog.Level.WARN,
            DiagnosticsLog.Category.ENGINE,
            "$operation failed (${result.message}); running the emergency display reset"
        )
        runCatching {
            DisplayEmergencyReset.clearRootTransforms(
                context = context.takeIf { clearsSecureRows(kind) },
                roots = clearsRootTransforms(kind)
            )
        }.onFailure { Log.w(logTag, "escalated hard reset failed: ${it.message}") }
    }

    /**
     * The driver for the tint restored before the first unlock: the root one
     * the unlocked service would pick, when it answers. KernelSU and Magisk
     * keep their grants in /data/adb, outside credential-encrypted storage, so
     * `su` usually works on the lock screen, and only a compositor-level driver
     * reaches it: the Overlay window is drawn below the keyguard. Overlay stays
     * the fallback for a driver that does not answer.
     */
    private suspend fun directBootEngineFor(engine: EngineKindDto): ColorEngine {
        val overlay = probe.engineOf(EngineKind.OVERLAY) ?: OverlayEngine()
        suspend fun ifAvailable(kind: EngineKind): ColorEngine? {
            val candidate = probe.engineOf(kind) ?: return null
            return candidate.takeIf { runCatching { it.isAvailable(context) }.getOrDefault(false) }
        }
        return when (engine) {
            // Root first when it answers. No targets: Night Light stays ahead
            // of the overlay here even for a preset outside its range (C364):
            // this restore is for the lock screen, which an overlay window
            // never covers, so a clamped tint beats none.
            EngineKindDto.Auto -> probe.pickBest(context) ?: overlay
            EngineKindDto.ColorDisplayManager -> ifAvailable(EngineKind.COLOR_DISPLAY_MANAGER) ?: overlay
            EngineKindDto.Overlay -> overlay
            EngineKindDto.SurfaceFlinger -> ifAvailable(EngineKind.SURFACE_FLINGER) ?: overlay
            EngineKindDto.Kcal -> ifAvailable(EngineKind.KCAL) ?: overlay
        }
    }

    internal data class FadePlan(val frames: Int, val frameMs: Long)

    internal companion object {
        private val FADE_OUT_TARGET: Pair<Boolean, LumenMatrix> = false to LumenMatrix.IDENTITY

        /** Average change per frame, as a fraction of full scale. */
        const val FADE_STEP = 0.01f
        const val MAX_FADE_FRAMES = 200

        /**
         * Frames for a fade from [from] to [to]: enough that no coefficient of
         * the compositor's matrix moves more than [FADE_STEP] per frame on
         * average, and no more than [minFrameMs] allows in [durationMs].
         */
        internal fun planFade(
            from: LumenMatrix,
            to: LumenMatrix,
            durationMs: Long,
            minFrameMs: Long
        ): FadePlan {
            val a = from.toSurfaceFlinger16()
            val b = to.toSurfaceFlinger16()
            var delta = abs(from.effectiveDim - to.effectiveDim)
            for (i in a.indices) delta = maxOf(delta, abs(a[i] - b[i]))
            val byChange = ceil(delta / FADE_STEP).toInt()
            val byTime = (durationMs / minFrameMs.coerceAtLeast(1L)).toInt()
            val frames = minOf(byChange, byTime, MAX_FADE_FRAMES).coerceAtLeast(1)
            return FadePlan(frames = frames, frameMs = (durationMs / frames).coerceAtLeast(1L))
        }

        /** Smoothstep: slow at both ends, fastest in the middle. */
        internal fun easeInOut(t: Float): Float {
            val u = t.coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }

        /**
         * Whether a pinned (non-Auto) driver should be used for this apply.
         *
         * Normally the probe decides. When the user has force-pinned the
         * driver, their choice wins: `su` detection is unreliable on
         * root-hiding setups and a probe that times out on an unanswered
         * Magisk prompt reads as "no root", so an unavailable verdict is not
         * proof (closed issue #16, roadmap C253). Forcing means the apply
         * either works or reports a concrete failure, instead of the app
         * quietly reverting the user's selection to Auto.
         */
        /**
         * Which transform family a failed driver could have left behind.
         *
         * C341: the escalation used to clear all three regardless. A root
         * driver whose `su` call was denied would zero the secure rows, which
         * that driver never writes and the user may well have set themselves,
         * and changing driver on the Driver tab is a routine action rather than
         * an emergency.
         */
        internal fun clearsSecureRows(kind: EngineKind): Boolean =
            kind == EngineKind.COLOR_DISPLAY_MANAGER

        internal fun clearsRootTransforms(kind: EngineKind): Boolean =
            kind == EngineKind.SURFACE_FLINGER || kind == EngineKind.KCAL

        /**
         * The overlay driver holds a window rather than persistent system
         * state, so there is nothing for the blunt reset to clear on its
         * behalf; `hardClearOutputs` removes the window directly.
         */
        internal fun escalatesToBluntReset(kind: EngineKind): Boolean =
            clearsSecureRows(kind) || clearsRootTransforms(kind)

        internal fun honourPinnedEngine(
            forcePinned: Boolean,
            probeSaysAvailable: Boolean
        ): Boolean = DriverProbe.honourPinnedEngine(forcePinned, probeSaysAvailable)
    }
}
