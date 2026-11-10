package com.linbit.linstor.api.rest.v1.config;

import com.linbit.linstor.annotation.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * fix-24b (E2): byte budget for large REST answers that are rendered but not yet written to the client.
 *
 * A rendered answer is held in memory until the client has read it. Without a bound, N clients that do not read
 * hold N rendered bodies (LINBIT/linstor-server#572). Each large answer takes a lease for its size before it is
 * written and returns it when the write ends (success, error or write timeout). An answer that does not fit the
 * global budget, its client's byte budget or its client's count of large answers is refused (503).
 */
public final class ResponseBudget
{
    public static final String ENV_GLOBAL_MIB = "LINSTOR_CONTROLLER_REST_RESPONSE_BUDGET_MIB";
    public static final String ENV_PER_CLIENT_MIB = "LINSTOR_CONTROLLER_REST_RESPONSE_BUDGET_PER_CLIENT_MIB";
    public static final String ENV_PER_CLIENT_COUNT = "LINSTOR_CONTROLLER_REST_RESPONSE_BUDGET_PER_CLIENT_COUNT";

    static final long MIB = 1024L * 1024L;
    static final long DFLT_PER_CLIENT_BYTES = 64 * MIB;
    static final int DFLT_PER_CLIENT_COUNT = 4;
    /** Default global budget: this fraction (1/n) of the JVM's max heap. */
    static final int DFLT_GLOBAL_HEAP_DIVISOR = 4;
    static final long MAX_MIB = 1024L * 1024L;
    static final int MAX_COUNT = 10_000;
    /**
     * Safety net: a lease whose write never ran or never ended (no write timeout reached it) is returned after this
     * time. A normal write ends well before: success, IOException or the transport write timeout.
     */
    static final long LEASE_MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final long globalCapBytes;
    private final long perClientCapBytes;
    private final int perClientCapCount;

    private long globalUsedBytes;
    private final Map<String, long[]> perClient = new HashMap<>(); // client -> {bytes, count}
    private final LinkedHashSet<Lease> active = new LinkedHashSet<>(); // oldest first
    private final NanoClock clock;
    private long expiredCount;

    @FunctionalInterface
    interface NanoClock
    {
        long nanoTime();
    }

    public ResponseBudget(long globalCapBytesRef, long perClientCapBytesRef, int perClientCapCountRef)
    {
        this(globalCapBytesRef, perClientCapBytesRef, perClientCapCountRef, System::nanoTime);
    }

    ResponseBudget(long globalCapBytesRef, long perClientCapBytesRef, int perClientCapCountRef, NanoClock clockRef)
    {
        clock = clockRef;
        if (globalCapBytesRef <= 0 || perClientCapBytesRef <= 0 || perClientCapCountRef <= 0)
        {
            throw new IllegalArgumentException("response budget caps must be positive");
        }
        globalCapBytes = globalCapBytesRef;
        perClientCapBytes = perClientCapBytesRef;
        perClientCapCount = perClientCapCountRef;
    }

    /** Caps from the environment; unset, unparsable or out-of-range values use the defaults. */
    public static ResponseBudget fromEnvironment()
    {
        long globalBytes = mibOrDefault(System.getenv(ENV_GLOBAL_MIB), -1);
        if (globalBytes <= 0)
        {
            globalBytes = Runtime.getRuntime().maxMemory() / DFLT_GLOBAL_HEAP_DIVISOR;
        }
        long perClientBytes = mibOrDefault(System.getenv(ENV_PER_CLIENT_MIB), DFLT_PER_CLIENT_BYTES);
        int perClientCount = (int) longOrDefault(System.getenv(ENV_PER_CLIENT_COUNT), 1, MAX_COUNT, DFLT_PER_CLIENT_COUNT);
        return new ResponseBudget(globalBytes, perClientBytes, perClientCount);
    }

    static long mibOrDefault(@Nullable String value, long dfltBytes)
    {
        long mib = longOrDefault(value, 1, MAX_MIB, -1);
        return mib > 0 ? mib * MIB : dfltBytes;
    }

    static long longOrDefault(@Nullable String value, long min, long max, long dflt)
    {
        long ret = dflt;
        if (value != null)
        {
            try
            {
                long parsed = Long.parseLong(value.trim());
                if (parsed >= min && parsed <= max)
                {
                    ret = parsed;
                }
            }
            catch (NumberFormatException ignored)
            {
                // keep the default
            }
        }
        return ret;
    }

    /**
     * Takes a lease for <code>bytes</code>, or returns null if the answer does not fit. Close the lease when the write
     * ends.
     */
    public synchronized @Nullable Lease tryAcquire(String client, long bytes)
    {
        expireOldLeases();
        @Nullable Lease lease = null;
        long[] used = perClient.get(client);
        long clientBytes = used == null ? 0 : used[0];
        long clientCount = used == null ? 0 : used[1];
        if (globalUsedBytes + bytes <= globalCapBytes &&
            clientBytes + bytes <= perClientCapBytes &&
            clientCount + 1 <= perClientCapCount)
        {
            if (used == null)
            {
                used = new long[2];
                perClient.put(client, used);
            }
            used[0] += bytes;
            used[1]++;
            globalUsedBytes += bytes;
            lease = new Lease(client, bytes, clock.nanoTime());
            active.add(lease);
        }
        return lease;
    }

    private void expireOldLeases()
    {
        long now = clock.nanoTime();
        Iterator<Lease> iter = active.iterator();
        while (iter.hasNext())
        {
            Lease oldest = iter.next();
            if (now - oldest.acquiredNanos < LEASE_MAX_AGE_NANOS)
            {
                break;
            }
            iter.remove();
            if (oldest.markClosed())
            {
                releaseLocked(oldest.client, oldest.bytes);
                expiredCount++;
            }
        }
    }

    /** Leases returned by the age safety net since start; nonzero means a write path did not return its lease. */
    public synchronized long getExpiredCount()
    {
        return expiredCount;
    }

    private synchronized void release(Lease lease)
    {
        active.remove(lease);
        releaseLocked(lease.client, lease.bytes);
    }

    private void releaseLocked(String client, long bytes)
    {
        globalUsedBytes -= bytes;
        long[] used = perClient.get(client);
        if (used != null)
        {
            used[0] -= bytes;
            used[1]--;
            if (used[1] <= 0)
            {
                perClient.remove(client);
            }
        }
    }

    public synchronized long getGlobalUsedBytes()
    {
        return globalUsedBytes;
    }

    // package-private for tests
    synchronized java.util.Set<String> getClientKeys()
    {
        return new java.util.HashSet<>(perClient.keySet());
    }

    public synchronized int getClientCount()
    {
        return perClient.size();
    }

    public long getGlobalCapBytes()
    {
        return globalCapBytes;
    }

    public long getPerClientCapBytes()
    {
        return perClientCapBytes;
    }

    public int getPerClientCapCount()
    {
        return perClientCapCount;
    }

    public final class Lease implements AutoCloseable
    {
        private final String client;
        private final long bytes;
        private final long acquiredNanos;
        private boolean closed;

        private Lease(String clientRef, long bytesRef, long acquiredNanosRef)
        {
            client = clientRef;
            bytes = bytesRef;
            acquiredNanos = acquiredNanosRef;
        }

        /** Marks the lease closed; true if this call closed it, false if it was already closed. */
        private synchronized boolean markClosed()
        {
            boolean wasOpen = !closed;
            closed = true;
            return wasOpen;
        }

        /** Idempotent. */
        @Override
        public void close()
        {
            if (markClosed())
            {
                release(this);
            }
        }
    }
}
