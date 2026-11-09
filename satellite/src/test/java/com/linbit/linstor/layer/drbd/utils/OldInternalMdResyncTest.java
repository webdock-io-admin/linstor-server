package com.linbit.linstor.layer.drbd.utils;

import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.testutils.EmptyErrorReporter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class OldInternalMdResyncTest
{
    private static final String RSC = "cpz-001";
    /** the 2026-11-05 case: old size 10485768 KiB, 7 peer slots, grown to 41943048 KiB */
    private static final long OLD_NET_KIB = 10_485_768L;
    private static final long NEW_NET_KIB = 41_943_048L;
    private static final short PEER_SLOTS = 7;
    /** where "drbdadm verify" found the stale old superblock: sector 20976088 */
    private static final long INCIDENT_SUPERBLOCK_KIB = 20_976_088L / 2;

    private final List<List<String>> commands = new ArrayList<>();
    /** answers for "drbdsetup status --json", in order; the last one repeats */
    private final Deque<String> statusAnswers = new ArrayDeque<>();
    private ExtCmdFactory extCmdFactory;
    private int failVerifyForPeer = -1;
    private long nowMs = 0;

    private static String peer(int nodeId, String repl, String peerDisk, long oos)
    {
        return "{\"peer-node-id\":" + nodeId + ",\"peer_devices\":[{\"volume\":0,\"replication-state\":\"" + repl +
            "\",\"peer-disk-state\":\"" + peerDisk + "\",\"out-of-sync\":" + oos + "}]}";
    }

    private static String status(String... peers)
    {
        return "[{\"name\":\"" + RSC + "\",\"connections\":[" + String.join(",", peers) + "]}]";
    }

    private static final String BOTH_CLEAN = status(
        peer(1, "Established", "UpToDate", 0),
        peer(2, "Established", "UpToDate", 0)
    );

    @Before
    public void setUp() throws Exception
    {
        ExtCmd extCmd = Mockito.mock(ExtCmd.class);
        when(extCmd.exec(any(String[].class))).thenAnswer(
            inv ->
            {
                List<String> cmd = new ArrayList<>();
                for (Object arg : inv.getArguments())
                {
                    if (arg instanceof String[] argArr)
                    {
                        cmd.addAll(Arrays.asList(argArr));
                    }
                    else
                    {
                        cmd.add((String) arg);
                    }
                }
                commands.add(cmd);
                String out = "";
                int exit = 0;
                if (cmd.get(1).equals("status"))
                {
                    out = statusAnswers.size() > 1 ? statusAnswers.removeFirst() : statusAnswers.getFirst();
                }
                else if (cmd.get(1).equals("verify") && cmd.get(3).equals(Integer.toString(failVerifyForPeer)))
                {
                    exit = 1;
                }
                return new OutputData(
                    cmd.toArray(new String[0]),
                    out.getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    exit
                );
            }
        );
        extCmdFactory = Mockito.mock(ExtCmdFactory.class);
        when(extCmdFactory.create()).thenReturn(extCmd);
    }

    private void run(long newNetKiB, List<Integer> peers)
    {
        OldInternalMdResync.run(
            extCmdFactory,
            new EmptyErrorReporter(),
            RSC,
            0,
            OLD_NET_KIB,
            newNetKiB,
            PEER_SLOTS,
            peers,
            millis -> nowMs += millis,
            () -> nowMs
        );
    }

    private List<List<String>> commandsOf(String sub)
    {
        List<List<String>> ret = new ArrayList<>();
        for (List<String> cmd : commands)
        {
            if (cmd.get(1).equals(sub))
            {
                ret.add(cmd);
            }
        }
        return ret;
    }

    @Test
    public void rangeCoversTheOldSuperblockOfTheIncident() throws Exception
    {
        long[] range = OldInternalMdResync.oldMdRangeKiB(OLD_NET_KIB, NEW_NET_KIB, PEER_SLOTS);
        assertThat(range[0]).isEqualTo(OLD_NET_KIB);
        // the stale 4 KiB superblock block found by verify lies inside the range
        assertThat(range[1]).isGreaterThanOrEqualTo(INCIDENT_SUPERBLOCK_KIB + 4);
        // bounded: the meta-data of a 10 GiB volume plus the slack, not the whole grown region
        assertThat(range[1] - range[0]).isLessThan(8 * 1024);
    }

    @Test
    public void rangeIsClampedToTheNewSize() throws Exception
    {
        long[] range = OldInternalMdResync.oldMdRangeKiB(OLD_NET_KIB, OLD_NET_KIB + 100, PEER_SLOTS);
        assertThat(range[1]).isEqualTo(OLD_NET_KIB + 100);
    }

    @Test
    public void verifiesEachPeerAndDisconnectsOnlyTheDifferingOne() throws Exception
    {
        statusAnswers.add(BOTH_CLEAN);
        statusAnswers.add(status(peer(1, "VerifyS", "UpToDate", 0), peer(2, "VerifyS", "UpToDate", 0)));
        statusAnswers.add(status(peer(1, "Established", "UpToDate", 4), peer(2, "Established", "UpToDate", 0)));

        run(NEW_NET_KIB, Arrays.asList(1, 2));

        long[] range = OldInternalMdResync.oldMdRangeKiB(OLD_NET_KIB, NEW_NET_KIB, PEER_SLOTS);
        assertThat(commandsOf("verify")).containsExactly(
            Arrays.asList("drbdsetup", "verify", RSC, "1", "0", "--start=" + range[0] + "k", "--stop=" + range[1] + "k"),
            Arrays.asList("drbdsetup", "verify", RSC, "2", "0", "--start=" + range[0] + "k", "--stop=" + range[1] + "k")
        );
        assertThat(commandsOf("disconnect")).containsExactly(Arrays.asList("drbdsetup", "disconnect", RSC, "1"));
    }

    /** out-of-sync in drbdsetup status is volume-wide: a peer that is still catching up is not verified at all */
    @Test
    public void peerWithPendingOutOfSyncIsNotVerified()
    {
        statusAnswers.add(status(peer(1, "SyncSource", "Inconsistent", 8), peer(2, "Established", "UpToDate", 0)));
        statusAnswers.add(status(peer(1, "SyncSource", "Inconsistent", 8), peer(2, "Established", "UpToDate", 0)));

        run(NEW_NET_KIB, Arrays.asList(1, 2));

        assertThat(commandsOf("verify")).extracting(cmd -> cmd.get(3)).containsExactly("2");
        assertThat(commandsOf("disconnect")).isEmpty();
    }

    @Test
    public void failedVerifyIsNotDisconnected()
    {
        failVerifyForPeer = 2;
        statusAnswers.add(BOTH_CLEAN);
        statusAnswers.add(status(peer(1, "Established", "UpToDate", 4), peer(2, "Established", "UpToDate", 8)));

        run(NEW_NET_KIB, Arrays.asList(1, 2));

        assertThat(commandsOf("verify")).hasSize(2);
        assertThat(commandsOf("disconnect")).containsExactly(Arrays.asList("drbdsetup", "disconnect", RSC, "1"));
    }

    @Test
    public void verifyThatNeverFinishesDisconnectsNothing()
    {
        statusAnswers.add(BOTH_CLEAN);
        statusAnswers.add(status(peer(1, "VerifyS", "UpToDate", 4), peer(2, "VerifyS", "UpToDate", 4)));

        run(NEW_NET_KIB, Arrays.asList(1, 2));

        assertThat(commandsOf("disconnect")).isEmpty();
        assertThat(nowMs).isGreaterThanOrEqualTo(OldInternalMdResync.MAX_WAIT_MS);
    }

    @Test
    public void noPeersOrNoGrowthRunsNothing()
    {
        run(NEW_NET_KIB, Collections.emptyList());
        run(OLD_NET_KIB, Arrays.asList(1, 2));

        assertThat(commands).isEmpty();
    }
}
