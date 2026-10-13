// ---------------------------------------------------------------------------------------------------- dynamic choices, asynchronous (SPEC_WRONG_SCIENCE section 5)
/** The ChoicesFrom lookups of one window. Every request is identified by (form generation, app, tool, parameter, the values it depends on, request id); a reply is applied only if ALL of
 *  those still hold when it arrives (5.2), so a late answer of another form, another app or an older question is dropped, with its error and its tooltip. The rules (5.1 - 5.6):
 *  - the form generation is process-wide and never reset: it grows whenever a form is built or destroyed (a tool or app is chosen, the window closes); the request id is per window and never reset;
 *  - a change of a value a parameter depends on invalidates at once (the latest id of the parameter moves on and its choices are marked pending); the pause before the new question
 *    (LcConst.CHOICES_DEBOUNCE_MS) only delays the question, never the invalidation;
 *  - at most one lookup is in flight per window (one thread, no thread per lookup), the waiting ones are coalesced (one slot per parameter, a newer question replaces an older one), at most
 *    LcConst.LOOKUP_PENDING_MAX wait (the oldest is dropped and logged) and a lookup that takes longer than LcConst.LOOKUP_TIMEOUT_SECONDS is ended with its worker;
 *  - a lookup runs on a worker leased from the window's LcWorkers (never a running tool's) and while a run is active it waits (LcConst.CHOICES_BUSY_RETRY_MS);
 *  - closing the window drops the waiting lookups, ends the one in flight and stops the thread. */
class LcLookups {
    /** Process-wide and never reset (5.1). */
    static final java.util.concurrent.atomic.AtomicInteger FORM_GENERATION = new java.util.concurrent.atomic.AtomicInteger(0)
    private static final java.util.concurrent.atomic.AtomicInteger WINDOWS = new java.util.concurrent.atomic.AtomicInteger(0)

    final LcDialog dialog
    private final java.util.concurrent.ExecutorService executor
    private final Object lock = new Object()
    private final Map<String, Map> waiting = new LinkedHashMap<String, Map>()       // parameter -> its newest question that has not started
    private final Map<String, Integer> latest = [:]                                // parameter -> the id of the newest question issued
    private final Set<String> pendingParams = new HashSet<String>()                  // parameters whose choices are not known now (a dependency changed)
    private int nextId = 0
    private boolean drainQueued = false
    private volatile Map inFlight = null
    private final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger(0)      // lookups being answered right now (counted in `answer`, whoever calls it)
    private volatile boolean closed = false
    // what the tests observe
    final java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger(0)
    final java.util.concurrent.atomic.AtomicInteger maxWaiting = new java.util.concurrent.atomic.AtomicInteger(0)
    final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger(0)
    final java.util.concurrent.atomic.AtomicInteger dropped = new java.util.concurrent.atomic.AtomicInteger(0)
    final List<String> droppedReplies = Collections.synchronizedList(new ArrayList<String>())      // parameter names whose reply was dropped as stale
    final String threadName

    LcLookups(LcDialog dialog) {
        this.dialog = dialog
        this.threadName = "lc-lookups-" + WINDOWS.incrementAndGet()
        this.executor = java.util.concurrent.Executors.newSingleThreadExecutor({ Runnable r -> Thread t = new Thread(r, threadName); t.daemon = true; return t } as java.util.concurrent.ThreadFactory)
    }

    /** The time limit of one lookup: LcConst.LOOKUP_TIMEOUT_SECONDS, or the system property when it holds a positive whole number (the tests shorten it; anything else is logged once and ignored). */
    static int timeoutSeconds() {
        String text = System.getProperty(LcConst.LOOKUP_TIMEOUT_PROPERTY)
        if (!text) return LcConst.LOOKUP_TIMEOUT_SECONDS
        try {
            int value = Integer.parseInt(text.trim())
            if (value > 0) return value
        } catch (NumberFormatException e) {
            LcLog.once("lookup-timeout-text:" + text, "system property " + LcConst.LOOKUP_TIMEOUT_PROPERTY + " = '" + text + "' is not a whole number (" + e.message + ")")
        }
        LcLog.once("lookup-timeout:" + text, "system property " + LcConst.LOOKUP_TIMEOUT_PROPERTY + " = '" + text + "' is ignored: the default of " + LcConst.LOOKUP_TIMEOUT_SECONDS + " s is used")
        return LcConst.LOOKUP_TIMEOUT_SECONDS
    }

    /** A form was built or destroyed: the generation moves on (5.1) and the questions that wait belong to a form that is gone. */
    static int newGeneration() { return FORM_GENERATION.incrementAndGet() }

    /** Forget what waits (the form it belonged to is gone). The question in flight, if any, is dropped when it answers. */
    void forgetWaiting() { synchronized (lock) { waiting.clear(); pendingParams.clear() } }

    /** The id of the newest question issued for `param` (0 when none). */
    int latestId(String param) { synchronized (lock) { return latest[param] ?: 0 } }

    boolean isPending(String param) { synchronized (lock) { return pendingParams.contains(param) } }

    int inFlightCount() { return inFlight != null ? 1 : 0 }

    /** A value that `param` depends on changed: its newest id moves on, so every older question (waiting, in flight or answered) is stale from now. FX thread. */
    void invalidate(String param) {
        synchronized (lock) {
            latest[param] = ++nextId
            waiting.remove(param)
        }
    }

    /** The choices of `param` are not known now (they are shown as pending until a current answer arrives). */
    void markPending(String param) { synchronized (lock) { pendingParams << param } }

    /** Asks for the choices of `param`. `request` is what the source tool receives (the values the parameter depends on); it is also the "dependencies" of the question. */
    void ask(Map p, Map request) {
        if (closed) return
        Map entry
        synchronized (lock) {
            int id = ++nextId
            latest[p.name as String] = id
            entry = [gen: FORM_GENERATION.get(), app: dialog.appBox.value, tool: dialog.currentTool?.id, param: p.name, source: p.choices_from, deps: LcJson.toJson(request), request: new LinkedHashMap(request), id: id, app_map: dialog.apps[dialog.appBox.value]]
            waiting.remove(p.name as String)        // a newer question replaces an older one that has not started (5.4); the new one goes to the end
            waiting[p.name as String] = entry
            while (waiting.size() > LcConst.LOOKUP_PENDING_MAX) {
                String oldest = waiting.keySet().first()
                waiting.remove(oldest)
                dropped.incrementAndGet()
                LcLog.warn("choices lookup of '" + oldest + "' dropped: more than " + LcConst.LOOKUP_PENDING_MAX + " lookups were waiting")
            }
            raise(maxWaiting, waiting.size())
            if (!drainQueued) { drainQueued = true; executor.execute({ -> drain() } as Runnable) }
        }
    }

    private static void raise(java.util.concurrent.atomic.AtomicInteger counter, int value) {
        int seen = counter.get()
        while (value > seen && !counter.compareAndSet(seen, value)) seen = counter.get()
    }

    /** The lookup thread: takes the oldest waiting question and answers it, until none waits. */
    private void drain() {
        while (!closed) {
            Map next
            synchronized (lock) {
                if (waiting.isEmpty()) { drainQueued = false; return }
                String first = waiting.keySet().first()
                next = waiting.remove(first)
                inFlight = next
            }
            try { answer(next) }
            catch (Exception e) {      // broad on purpose (the thread of the window must survive whatever a source tool or its worker does): the lookup ends with its text field
                LcLog.warn("choices of '" + next.param + "' could not be loaded", e)
                reply(next, null, e.class.simpleName + ": " + e.message)
            } finally { inFlight = null }
        }
        synchronized (lock) { drainQueued = false }
    }

    /** One lookup: stale ones are not even started; a run in progress makes it wait; otherwise the source tool runs on a leased worker with the time limit. */
    private void answer(Map req) {
        raise(maxInFlight, running.incrementAndGet())
        try { answerNow(req) } finally { running.decrementAndGet() }
    }

    private void answerNow(Map req) {
        if (!current(req, false)) return                 // another form, another question, or other values already: nothing to ask
        if (dialog.running) { retryLater(); return }
        LcLease lease = dialog.workers.lease(req.app as String, req.app_map as Map, LcWorkers.LOOKUP)
        if (lease == null) { retryLater(); return }   // a run is active: lookups wait
        File tmp = Files.createTempDirectory("lcqchoices_").toFile()
        List options = null
        String why = null
        try {
            started.incrementAndGet()
            Map request = new LinkedHashMap(req.request as Map)
            request["_job_dir"] = tmp.absolutePath
            Map source = req.source as Map
            Map outcome = (lease.worker as LcWorker).run(source.tool as String, request, null, timeoutSeconds())
            if (outcome.responseType == "COMPLETION") {
                Object found = (outcome.outputs?.results ?: []).find { it.type == "values" && it.values?.get(source.field ?: "choices") instanceof List }
                options = found?.values?.get(source.field ?: "choices")?.collect { it.toString() }
                if (options == null) why = "the source tool '" + source.tool + "' did not return a list"
            } else {
                why = "the source tool '" + source.tool + "' did not complete (" + outcome.responseType + ": " + outcome.error + ")"
                if (outcome.responseType == "CRASH") dialog.workers.discard(lease.worker)
            }
        } finally {
            dialog.workers.release(lease)
            LcLog.deleteTemp(tmp)
        }
        reply(req, options, why)
    }

    private void retryLater() {
        Platform.runLater { if (!closed) dialog.scheduleChoices(LcConst.CHOICES_BUSY_RETRY_MS) }
    }

    /** Hands the answer to the FX thread, which applies it only if the question is still the current one. */
    private void reply(Map req, List options, String why) {
        Platform.runLater { apply(req, options, why) }
    }

    /** True when the question is still the one the form wants: same form generation, app, tool, newest id of the parameter and (when `values`) the dependencies as they are NOW. */
    private boolean current(Map req, boolean values) {
        if (closed || req.gen != FORM_GENERATION.get()) return false
        synchronized (lock) { if (latest[req.param as String] != req.id) return false }
        if (!values) return true
        Map p = dialog.currentTool?.inputs?.find { it.name == req.param }
        if (p == null || dialog.appBox.value != req.app || dialog.currentTool.id != req.tool) return false
        Map now = dialog.choiceRequest(p)
        return now != null && LcJson.toJson(now) == req.deps
    }

    /** FX thread: applies an answer when ALL of its identity still holds, else drops it whole (its error and tooltip too). */
    void apply(Map req, List options, String why) {
        if (!current(req, true)) { droppedReplies << (req.param as String); return }
        synchronized (lock) { pendingParams.remove(req.param as String) }
        dialog.applyChoices(req.param as String, options, why)
    }

    /** The window is closed: waiting lookups are dropped and the thread stops (the one in flight ends with its worker, which the window closes). */
    void close() {
        closed = true
        synchronized (lock) { waiting.clear(); pendingParams.clear() }
        executor.shutdownNow()
    }

    /** True when the lookup thread is gone (tests). */
    boolean stopped() { return executor.isTerminated() }

    boolean awaitStopped(long millis) { return executor.awaitTermination(millis, TimeUnit.MILLISECONDS) }
}
