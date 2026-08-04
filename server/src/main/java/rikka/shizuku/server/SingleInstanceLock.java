package rikka.shizuku.server;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

import rikka.shizuku.server.util.Logger;

/**
 * The invariant that only one {@code shizuku_plus_server} may exist, enforced by the server itself.
 *
 * <p><b>Why this cannot live in the starter.</b> {@code starter.cpp} kills any existing server and
 * then forks a new one, with no lock in between. Two starters running close together each sweep,
 * each find nothing to kill because neither server exists yet, and each then forks one — measured
 * on 2026-08-04 as two servers parented to init, started 44 ms apart. The starter has seven
 * independent invocation sites (boot receiver, tile, adb start, home card, …), so a boot receiver
 * firing while the app is opened is enough. Reordering cannot fix it; only something atomic can,
 * and the natural atomic thing is a lock held by the process that must be unique.
 *
 * <p><b>Why a duplicate is not merely wasteful.</b> Each server loads the grant table once at
 * startup and never re-reads it, and every server pushes its binder to every client at every
 * launch, while a client keeps the first binder that arrives and drops the rest
 * ({@code ShizukuProvider.handleSendBinder}). So which server a client ends up bound to is a coin
 * toss, and a grant made after both started lives in exactly one of them: authorisation then works
 * or fails at random across cold starts, with both the manager and the refusing client telling the
 * truth about different servers. Worse, both flush the same file through their own {@code
 * AtomicFile} from their own in-memory copy, so the loser's flush can silently erase the table.
 *
 * <p><b>Why {@code flock} rather than "the new server kills the old one".</b> The older server may
 * be holding live user-service bindings for apps that are working fine; killing it tears those
 * down. The loser of the race is the one that should stand down, and an advisory file lock picks
 * that winner atomically no matter how many starters fire. It also cannot go stale — the kernel
 * drops it when the holder dies, including on SIGKILL, so there is no cleanup path to get wrong.
 *
 * <p><b>Failure is open, not closed.</b> If the lock file cannot be created or locked at all —
 * a read-only path, an SELinux denial, an OEM oddity — this returns {@code true} and the server
 * starts unlocked. A device where the lock is unavailable must still get a working Shizuku; the
 * duplicate is a rare race, and refusing to start would turn it into a total outage.
 */
public final class SingleInstanceLock {

    private static final Logger LOGGER = new Logger("SingleInstance");

    /**
     * Namespaced to this fork for the same reason the grant table is (see
     * {@link ShizukuConfigManager}): the lock must exclude <em>our</em> servers from each other and
     * nothing else. A shared name would let stock Shizuku, or upstream's build, block ours.
     */
    private static final String LOCK_NAME = "shiroikuma-shizuku.lock";

    /**
     * Held for the life of the process. All three references are kept because a {@link FileLock} is
     * released when its channel is closed <em>or</em> collected — dropping them on the floor would
     * hand the lock back the moment GC ran, and the second server would then start hours later with
     * no trace of why.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private static RandomAccessFile lockRaf;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileChannel lockChannel;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileLock lock;

    private SingleInstanceLock() {
    }

    /**
     * @return {@code true} if this process may serve — either it took the lock, or the lock could
     * not be attempted at all. {@code false} only when another live server demonstrably holds it,
     * which is the caller's cue to exit without publishing any binder.
     */
    public static boolean acquire() {
        File file = lockFile();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            // The server runs as shell (adb start) or root (root start), and whichever created the
            // file first owns it. A zero-byte lock file carries nothing worth protecting, so open
            // it to everyone rather than let a root-created file lock out the next adb start.
            try {
                file.setReadable(true, false);
                file.setWritable(true, false);
            } catch (Throwable ignored) {
            }

            FileChannel channel = raf.getChannel();
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                LOGGER.e("another %s already holds %s — standing down so the running one keeps its "
                        + "clients and its grant table", ServerConstants.SERVER_NAME, file);
                closeQuietly(raf);
                return false;
            }

            lockRaf = raf;
            lockChannel = channel;
            lock = acquired;
            LOGGER.i("single-instance lock held on %s", file);
            return true;
        } catch (OverlappingFileLockException e) {
            // Only reachable if this process already holds it, which main() cannot do twice.
            LOGGER.w("single-instance lock already held by this process");
            return true;
        } catch (Throwable tr) {
            LOGGER.w(tr, "cannot take the single-instance lock at %s — starting unlocked", file);
            closeQuietly(raf);
            return true;
        }
    }

    /**
     * Where the lock lives, following {@code ShizukuConfigManager.getConfigFile()} exactly: the
     * server may be running as shell and may start before the user has unlocked, so it cannot use
     * the manager's data dir. {@code com.android.shell}'s DE storage is writable by the uid we
     * normally run as; {@code /data/local/tmp} is the fallback when it is not.
     *
     * <p>The {@code exists()} check comes first so that once either path has been used, every later
     * server agrees on it — two servers resolving to <em>different</em> files would not exclude each
     * other, which is the one way this could quietly do nothing.
     */
    private static File lockFile() {
        File shellFile = new File("/data/user_de/0/com.android.shell/" + LOCK_NAME);
        if (shellFile.exists()) {
            return shellFile;
        }
        try {
            File parent = shellFile.getParentFile();
            if (parent != null && parent.exists() && parent.canWrite()) {
                return shellFile;
            }
        } catch (Throwable ignored) {
        }
        return new File("/data/local/tmp/" + LOCK_NAME);
    }

    private static void closeQuietly(RandomAccessFile raf) {
        if (raf == null) return;
        try {
            raf.close();
        } catch (Throwable ignored) {
        }
    }
}
