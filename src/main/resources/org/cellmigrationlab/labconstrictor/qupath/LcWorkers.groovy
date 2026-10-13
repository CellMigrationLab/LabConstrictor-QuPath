// ---------------------------------------------------------------------------------------------------- who owns the worker (SPEC_WRONG_SCIENCE 5.5, 5.6)
/** What the controller needs from a worker process: LcWorker implements it, the tests use a fake. */
interface LcWorkerLike {
    boolean alive()

    void close()

    void kill()
}

/** One job's hold on a worker. A job (a run or a lookup) takes a lease when it is submitted, uses ONLY that worker and gives the lease back when it ends: the worker the job started on is the
 *  worker it ends on, whatever else happens to the form meanwhile (an app switch, a rescan, a second lookup). */
class LcLease {
    final LcWorkerLike worker
    final String appName
    final String kind

    LcLease(LcWorkerLike worker, String appName, String kind) {
        this.worker = worker
        this.appName = appName
        this.kind = kind
    }
}

/** The ONE owner of the workers of a window: every start, reuse, release, reap and close goes through here, under one lock, so that each worker is owned once and closed once (5.5, 5.6).
 *  - At most one worker is kept for reuse (`current`, the one of the last app used). A lease for another app starts a new worker and retires the old one: it is closed at once when nothing holds it,
 *    else when its last lease ends. An app switch during a run therefore never replaces the run's worker: the run goes on with its own, the new app's worker is made for the next job.
 *  - A lookup never uses a worker a run is using and never starts while a run is active (`lease` answers null: the caller asks again later, LcConst.CHOICES_BUSY_RETRY_MS); a run waits for a
 *    lookup that holds the worker it needs (a lookup is short and has its own time limit).
 *  - After `closeAll` nothing is leased and no worker is made; a worker that was being made then is closed by the thread that made it (under the same lock, so the two cannot interleave). */
class LcWorkers {
    static final String RUN = "run"
    static final String LOOKUP = "lookup"
    static final int WAIT_STEP_MS = 100
    /** Test hook (tests only): called with a named point of lease / release / reap / close, so a test can interleave threads at fixed places. */
    static volatile Closure<Object> point = null
    /** Test hook (tests only): called while a new worker is being made, before it is stored. */
    static volatile Closure<Object> creating = null

    private final Object lock = new Object()
    private final Closure<LcWorkerLike> factory                 // (app map) -> a new worker
    private LcWorkerLike current                                // kept for reuse
    private String currentApp
    private final Map<LcWorkerLike, Integer> leases = new IdentityHashMap<LcWorkerLike, Integer>()
    private final Set<LcWorkerLike> retired = Collections.newSetFromMap(new IdentityHashMap<LcWorkerLike, Boolean>())
    private final Set<LcWorkerLike> closedWorkers = Collections.newSetFromMap(new IdentityHashMap<LcWorkerLike, Boolean>())
    private int runLeases = 0
    private boolean closed = false
    final List<Thread> closing = Collections.synchronizedList(new ArrayList<Thread>())        // the threads that close workers (tests join them)
    final List<LcWorkerLike> made = Collections.synchronizedList(new ArrayList<LcWorkerLike>())  // every worker this controller made (tests count them)

    LcWorkers(Closure<LcWorkerLike> factory) { this.factory = factory }

    private static void reach(String name) {
        Closure<Object> hook = point
        if (hook != null) hook.call(name)
    }

    /** The worker kept for reuse, or null. */
    LcWorkerLike getCurrent() { synchronized (lock) { return current } }

    String getCurrentApp() { synchronized (lock) { return current != null ? currentApp : null } }

    boolean isClosed() { synchronized (lock) { return closed } }

    /** How many leases hold `worker` now. */
    int leasesOf(LcWorkerLike worker) { synchronized (lock) { return leases.get(worker) ?: 0 } }

    /** A lease on a live worker of the app `appName` (described by `app`, the registry map the factory reads), started when there is none. null for a LOOKUP while a run is active. Throws
     *  IllegalStateException when the window is closed. */
    LcLease lease(String appName, Map app, String kind) {
        reach("lease:" + kind)
        synchronized (lock) {
            while (true) {
                if (closed) throw new IllegalStateException("the window is closed")
                if (kind == LOOKUP && runLeases > 0) return null
                boolean reusable = current != null && currentApp == appName && current.alive()
                if (reusable && kind == RUN && (leases.get(current) ?: 0) > 0) {      // a lookup holds it: wait for it (it is short), then use the same worker
                    lock.wait(WAIT_STEP_MS)
                    continue
                }
                if (reusable) return take(current, appName, kind)
                LcWorkerLike fresh = make(app)
                LcWorkerLike old = current
                current = fresh
                currentApp = appName
                if (old != null) retire(old)
                return take(fresh, appName, kind)
            }
        }
    }

    /** Called with the lock held. */
    private LcWorkerLike make(Map app) {
        Closure<Object> hook = creating
        if (hook != null) hook.call()
        LcWorkerLike fresh = factory.call(app)
        made << fresh
        reach("lease:made")
        return fresh
    }

    private LcLease take(LcWorkerLike worker, String appName, String kind) {
        leases.put(worker, (leases.get(worker) ?: 0) + 1)
        if (kind == RUN) runLeases++
        return new LcLease(worker, appName, kind)
    }

    /** Called with the lock held: a worker nobody holds is closed now, one that is held when its last lease ends. */
    private void retire(LcWorkerLike worker) {
        if ((leases.get(worker) ?: 0) > 0) retired << worker else closeOnce(worker, false)
    }

    /** Called with the lock held: closes `worker` on a thread of its own (a close waits for the process), at most once. */
    private void closeOnce(LcWorkerLike worker, boolean force) {
        if (!closedWorkers.add(worker)) return
        if (current.is(worker)) { current = null; currentApp = null }
        Thread thread = new Thread({ -> if (force) worker.kill() else worker.close() } as Runnable, "lc-worker-close")
        thread.daemon = true
        closing << thread
        thread.start()
    }

    /** Gives a lease back. A retired worker whose last lease this was is closed; a worker that died (or was killed by a time limit) is dropped from the cache. */
    void release(LcLease lease) {
        reach("release")
        synchronized (lock) {
            LcWorkerLike worker = lease.worker
            int left = (leases.get(worker) ?: 1) - 1
            if (left > 0) leases.put(worker, left) else leases.remove(worker)
            if (lease.kind == RUN) runLeases--
            if (left == 0 && retired.remove(worker)) closeOnce(worker, false)
            else if (left == 0 && !worker.alive() && current.is(worker)) closeOnce(worker, true)
            lock.notifyAll()
        }
    }

    /** Ends a worker that crashed or must not be used again: killed, and out of the cache, even while the lease on it is still held (the lease holder is finished with it). */
    void discard(LcWorkerLike worker) {
        synchronized (lock) {
            if (closedWorkers.contains(worker)) return
            retired.remove(worker)
            closeOnce(worker, true)
            lock.notifyAll()
        }
    }

    /** The reaper (idle timer, Restart worker): closes the kept worker when no job holds it. With `force` it is killed, held or not. Returns true when a worker was ended. */
    boolean reap(boolean force) {
        reach("reap")
        synchronized (lock) {
            if (current == null) return false
            if (!force && (leases.get(current) ?: 0) > 0) return false
            closeOnce(current, force)
            lock.notifyAll()
            return true
        }
    }

    /** The window is closed: no lease is given any more, every worker is closed (killed with `force`), held or not. A job that holds one of them ends with the worker. */
    void closeAll(boolean force) {
        reach("close")
        synchronized (lock) {
            closed = true
            List<LcWorkerLike> every = new ArrayList<LcWorkerLike>()
            if (current != null) every << current
            every.addAll(leases.keySet())
            every.addAll(retired)
            every.each { LcWorkerLike w -> closeOnce(w, force) }
            current = null
            currentApp = null
            retired.clear()
            lock.notifyAll()
        }
    }

    /** Waits for the threads that close workers (at most `millis` for all of them); true when none is alive any more. */
    boolean joinClosing(long millis) {
        long end = System.currentTimeMillis() + millis
        for (Thread thread : new ArrayList<Thread>(closing)) thread.join(Math.max(1L, end - System.currentTimeMillis()))
        return closing.every { !it.alive }
    }
}
