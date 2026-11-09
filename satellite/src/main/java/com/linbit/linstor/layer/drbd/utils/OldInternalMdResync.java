package com.linbit.linstor.layer.drbd.utils;

import com.linbit.Platform;
import com.linbit.drbd.md.MetaData;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.annotation.Nullable;
import com.linbit.linstor.layer.drbd.DrbdLayerSizeCalculator;
import com.linbit.linstor.logging.ErrorReporter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * After an online grow with "--assume-clean" of a volume with internal meta-data, DRBD moves the meta-data to the new
 * end of the device but leaves the old meta-data range (superblock, activity log, bitmap) in the data area, neither
 * zeroed nor marked out of sync (LINBIT/drbd#165). Each node keeps its own old bytes there, so a later
 * "drbdadm verify" reports them, and a diskless peer reading that range gets different data from different nodes.
 *
 * Opt-in via {@value #ENABLE_ENV}=true: verify only that range against each diskful peer that is Established and
 * UpToDate with nothing out of sync, and disconnect a peer for which the verify found differences; the "drbdadm adjust"
 * that follows a resize reconnects it and resyncs the marked blocks. The out-of-sync count in "drbdsetup status" is
 * volume-wide, which is why only peers with 0 out of sync before the verify are verified.
 * The range assumes LINSTOR's internal meta-data layout (1 activity-log stripe of 32 KiB, as in create-md).
 * Every failure is logged and ignored: the grow itself is never affected.
 */
public final class OldInternalMdResync
{
    public static final String ENABLE_ENV = "LINSTOR_SATELLITE_RESYNC_OLD_MD_AFTER_GROW";

    /** extra range after the computed meta-data size, to cover rounding and the ExactSize cap */
    static final long SLACK_KIB = 1024;
    static final long POLL_INTERVAL_MS = 200;
    static final long MAX_WAIT_MS = 10_000;

    private static final ObjectMapper OBJ_MAPPER = new ObjectMapper();

    @FunctionalInterface
    public interface Sleeper
    {
        void sleep(long millis) throws InterruptedException;
    }

    private OldInternalMdResync()
    {
    }

    public static boolean isEnabled()
    {
        return Platform.isLinux() && "true".equalsIgnoreCase(System.getenv(ENABLE_ENV));
    }

    /**
     * Range of the old internal meta-data in KiB, start inclusive, stop exclusive. The size uses the smallest bitmap
     * block size (largest bitmap), so the range covers any bitmap block size the volume may use.
     */
    static long[] oldMdRangeKiB(long oldNetKiB, long newNetKiB, short peerSlots) throws Exception
    {
        long mdKiB = new MetaData().getGrossSize(
            oldNetKiB,
            peerSlots,
            DrbdLayerSizeCalculator.FIXME_AL_STRIPES,
            DrbdLayerSizeCalculator.FIXME_AL_STRIPE_SIZE,
            MetaData.DRBD_DEFAULT_BM_BIT_COVER_kiB
        ) - oldNetKiB;
        return new long[] {oldNetKiB, Math.min(oldNetKiB + mdKiB + SLACK_KIB, newNetKiB)};
    }

    public static void run(
        ExtCmdFactory extCmdFactory,
        ErrorReporter errorReporter,
        String rscName,
        int vlmNr,
        long oldNetKiB,
        long newNetKiB,
        short peerSlots,
        Collection<Integer> peerNodeIds
    )
    {
        run(
            extCmdFactory,
            errorReporter,
            rscName,
            vlmNr,
            oldNetKiB,
            newNetKiB,
            peerSlots,
            peerNodeIds,
            Thread::sleep,
            () -> System.nanoTime() / 1_000_000L
        );
    }

    static void run(
        ExtCmdFactory extCmdFactory,
        ErrorReporter errorReporter,
        String rscName,
        int vlmNr,
        long oldNetKiB,
        long newNetKiB,
        short peerSlots,
        Collection<Integer> peerNodeIds,
        Sleeper sleeper,
        LongSupplier clockInMs
    )
    {
        if (peerNodeIds.isEmpty() || newNetKiB <= oldNetKiB)
        {
            return;
        }
        long[] range;
        try
        {
            range = oldMdRangeKiB(oldNetKiB, newNetKiB, peerSlots);
        }
        catch (Exception exc)
        {
            errorReporter.logWarning(
                "%s/%d: cannot compute the old meta-data range after the grow (%s), not verifying it",
                rscName,
                vlmNr,
                exc.getMessage()
            );
            return;
        }

        @Nullable JsonNode before = readStatus(extCmdFactory, errorReporter, rscName);
        if (before == null)
        {
            return;
        }
        List<Integer> started = new ArrayList<>();
        for (int peerNodeId : peerNodeIds)
        {
            @Nullable JsonNode peerDev = peerDevice(before, peerNodeId, vlmNr);
            boolean eligible = peerDev != null &&
                "Established".equals(peerDev.path("replication-state").asText("")) &&
                "UpToDate".equals(peerDev.path("peer-disk-state").asText("")) &&
                peerDev.path("out-of-sync").asLong(-1) == 0;
            if (!eligible)
            {
                errorReporter.logWarning(
                    "%s/%d: peer node-id %d is not Established/UpToDate with nothing out of sync; not verifying the " +
                        "old meta-data range against it",
                    rscName,
                    vlmNr,
                    peerNodeId
                );
            }
            else if (exec(
                extCmdFactory,
                errorReporter,
                DrbdAdm.DRBDSETUP_UTIL, "verify", rscName, Integer.toString(peerNodeId), Integer.toString(vlmNr),
                "--start=" + range[0] + "k",
                "--stop=" + range[1] + "k"
            ) != null)
            {
                started.add(peerNodeId);
            }
        }
        if (started.isEmpty())
        {
            return;
        }

        @Nullable JsonNode status = waitForVerifyDone(extCmdFactory, errorReporter, rscName, vlmNr, started, sleeper,
            clockInMs);
        if (status == null)
        {
            errorReporter.logWarning(
                "%s/%d: verify of the old meta-data range did not finish within %d ms; leaving it to the next resync",
                rscName,
                vlmNr,
                MAX_WAIT_MS
            );
            return;
        }
        for (int peerNodeId : started)
        {
            long outOfSync = outOfSyncKiB(status, peerNodeId, vlmNr);
            if (outOfSync > 0)
            {
                errorReporter.logInfo(
                    "%s/%d: old meta-data range differs from peer node-id %d (%d KiB out of sync); disconnecting " +
                        "so that the following adjust resyncs it",
                    rscName,
                    vlmNr,
                    peerNodeId,
                    outOfSync
                );
                exec(extCmdFactory, errorReporter, DrbdAdm.DRBDSETUP_UTIL, "disconnect", rscName,
                    Integer.toString(peerNodeId));
            }
        }
    }

    private static @Nullable JsonNode waitForVerifyDone(
        ExtCmdFactory extCmdFactory,
        ErrorReporter errorReporter,
        String rscName,
        int vlmNr,
        List<Integer> peerNodeIds,
        Sleeper sleeper,
        LongSupplier clockInMs
    )
    {
        long deadline = clockInMs.getAsLong() + MAX_WAIT_MS;
        @Nullable JsonNode result = null;
        boolean done = false;
        while (!done)
        {
            @Nullable String json = exec(extCmdFactory, errorReporter, DrbdAdm.DRBDSETUP_UTIL, "status", rscName,
                "--json");
            if (json != null)
            {
                try
                {
                    JsonNode status = OBJ_MAPPER.readTree(json);
                    boolean verifying = false;
                    for (int peerNodeId : peerNodeIds)
                    {
                        String state = replicationState(status, peerNodeId, vlmNr);
                        if (state.startsWith("Verify"))
                        {
                            verifying = true;
                        }
                    }
                    if (!verifying)
                    {
                        result = status;
                        done = true;
                    }
                }
                catch (Exception exc)
                {
                    errorReporter.logWarning("%s: cannot parse drbdsetup status --json: %s", rscName,
                        exc.getMessage());
                    done = true;
                }
            }
            if (!done)
            {
                if (clockInMs.getAsLong() >= deadline)
                {
                    done = true;
                }
                else
                {
                    try
                    {
                        sleeper.sleep(POLL_INTERVAL_MS);
                    }
                    catch (InterruptedException interruptedExc)
                    {
                        Thread.currentThread().interrupt();
                        done = true;
                    }
                }
            }
        }
        return result;
    }

    private static @Nullable JsonNode readStatus(ExtCmdFactory extCmdFactory, ErrorReporter errorReporter, String rscName)
    {
        @Nullable JsonNode ret = null;
        @Nullable String json = exec(extCmdFactory, errorReporter, DrbdAdm.DRBDSETUP_UTIL, "status", rscName, "--json");
        if (json != null)
        {
            try
            {
                ret = OBJ_MAPPER.readTree(json);
            }
            catch (Exception exc)
            {
                errorReporter.logWarning("%s: cannot parse drbdsetup status --json: %s", rscName, exc.getMessage());
            }
        }
        return ret;
    }

    private static @Nullable JsonNode peerDevice(JsonNode status, int peerNodeId, int vlmNr)
    {
        @Nullable JsonNode found = null;
        JsonNode rsc = status.isArray() && status.size() > 0 ? status.get(0) : status;
        for (JsonNode conn : rsc.path("connections"))
        {
            if (conn.path("peer-node-id").asInt(-1) == peerNodeId)
            {
                for (JsonNode peerDev : conn.path("peer_devices"))
                {
                    if (peerDev.path("volume").asInt(-1) == vlmNr)
                    {
                        found = peerDev;
                    }
                }
            }
        }
        return found;
    }

    static String replicationState(JsonNode status, int peerNodeId, int vlmNr)
    {
        @Nullable JsonNode peerDev = peerDevice(status, peerNodeId, vlmNr);
        return peerDev == null ? "" : peerDev.path("replication-state").asText("");
    }

    static long outOfSyncKiB(JsonNode status, int peerNodeId, int vlmNr)
    {
        @Nullable JsonNode peerDev = peerDevice(status, peerNodeId, vlmNr);
        return peerDev == null ? 0 : peerDev.path("out-of-sync").asLong(0);
    }

    /** returns stdout on success, null (after logging) on any failure */
    private static @Nullable String exec(ExtCmdFactory extCmdFactory, ErrorReporter errorReporter, String... cmd)
    {
        @Nullable String ret = null;
        try
        {
            OutputData out = extCmdFactory.create().exec(cmd);
            if (out.exitCode == 0)
            {
                ret = new String(out.stdoutData, StandardCharsets.UTF_8);
            }
            else
            {
                errorReporter.logWarning(
                    "'%s' failed with exit code %d: %s",
                    String.join(" ", cmd),
                    out.exitCode,
                    new String(out.stderrData, StandardCharsets.UTF_8).trim()
                );
            }
        }
        catch (Exception exc)
        {
            errorReporter.logWarning("'%s' failed: %s", String.join(" ", cmd), exc.getMessage());
        }
        return ret;
    }
}
