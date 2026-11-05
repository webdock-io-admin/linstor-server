package com.linbit.linstor.layer.drbd.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs a real drbdmeta (path in the environment variable LINSTOR_TEST_DRBDMETA; skipped if unset) and checks that
 * the one-write path leaves exactly the meta data that 32 "drbdmeta set-gi" calls leave: same "dump-md" for internal
 * and external meta data, with the GI strings that DrbdLayer builds for the UpToDate-race winner.
 */
public class DrbdMdV09PeerBitmapUuidsDrbdmetaTest
{
    private static final String DRBDMETA_ENV = "LINSTOR_TEST_DRBDMETA";
    private static final int NODE_ID_COUNT = 32;
    private static final int MINOR = 1000;
    private static final int MAX_PEERS = 7;
    private static final long DEVICE_SIZE = 64L << 20;
    private static final long TIMEOUT_SEC = 30;
    private static final String RANDOM_GID = "7F3C2B1A09886655";
    private static final String DAY0 = "B5605B0606F74AFE";
    // first, low, above 8 and last node-id: the loop writes the local GI before, between and after the others
    private static final int[] LOCAL_NODE_IDS = {0, 1, 9, 31};

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private String drbdmeta;

    @Before
    public void setUp()
    {
        drbdmeta = System.getenv(DRBDMETA_ENV);
        Assume.assumeTrue(DRBDMETA_ENV + " not set", drbdmeta != null && Files.isExecutable(Paths.get(drbdmeta)));
    }

    @Test
    public void internalMetaDataIsIdentical() throws Exception
    {
        for (int localNodeId : LOCAL_NODE_IDS)
        {
            assertIdentical(localNodeId, false);
        }
    }

    @Test
    public void externalMetaDataIsIdentical() throws Exception
    {
        assertIdentical(2, true);
    }

    private void assertIdentical(int localNodeId, boolean external) throws Exception
    {
        String mdType = external ? "flex-external" : "internal";
        DrbdGiStringBuilder localGi = new DrbdGiStringBuilder()
            .withCurrentUUID(RANDOM_GID)
            .withBitmapBaseDataUUID(DAY0)
            .withConsistent(true)
            .withUpToDate(true);
        DrbdGiStringBuilder otherGi = new DrbdGiStringBuilder().withBitmapBaseDataUUID(DAY0);

        Path loop = newDevice("loop-" + localNodeId + "-" + mdType);
        createMd(loop, mdType);
        for (int nodeId = 0; nodeId < NODE_ID_COUNT; nodeId++)
        {
            setGi(loop, mdType, nodeId, nodeId == localNodeId ? localGi : otherGi);
        }

        Path once = newDevice("once-" + localNodeId + "-" + mdType);
        createMd(once, mdType);
        DrbdMdV09PeerBitmapUuids.write(once, external, localNodeId, otherGi.getBitmapUuidIfOnlyField());
        setGi(once, mdType, localNodeId, localGi);

        String loopDump = dumpMd(loop, mdType);
        assertTrue(loopDump, loopDump.contains(DAY0.toLowerCase()) || loopDump.contains(DAY0));
        assertEquals("local node-id " + localNodeId + " " + mdType, loopDump, dumpMd(once, mdType));
    }

    @Test
    public void drbdmetaWorksWhileTheDeviceIsHeld() throws Exception
    {
        // the hold's plain read-only descriptor must not get in the way of drbdmeta (create-md, set-gi, dump-md)
        Path dev = newDevice("held");
        try (BackingDeviceHold hold = BackingDeviceHold.createEnabled(org.mockito.Mockito.mock(
            com.linbit.linstor.logging.ErrorReporter.class)))
        {
            hold.add(java.util.Collections.singletonList(dev.toString()));
            assertEquals(1, hold.getChannels().size());
            createMd(dev, "internal");
            setGi(dev, "internal", 0, new DrbdGiStringBuilder().withBitmapBaseDataUUID(DAY0));
            assertTrue(dumpMd(dev, "internal").contains(DAY0));
        }
    }

    private Path newDevice(String name) throws IOException
    {
        Path dev = tmpDir.getRoot().toPath().resolve(name + ".img");
        try (var file = new java.io.RandomAccessFile(dev.toFile(), "rw"))
        {
            file.setLength(DEVICE_SIZE);
        }
        return dev;
    }

    private void createMd(Path dev, String mdType) throws Exception
    {
        run(dev, mdType, "create-md", Integer.toString(MAX_PEERS));
    }

    private void setGi(Path dev, String mdType, int nodeId, DrbdGiStringBuilder gi) throws Exception
    {
        // the same command line as DrbdAdm.setGi
        drbdmeta(
            Arrays.asList(
                "--force", "--node-id", Integer.toString(nodeId), Integer.toString(MINOR), "v09", dev.toString(),
                mdType, "set-gi", gi.build()
            )
        );
    }

    private String dumpMd(Path dev, String mdType) throws Exception
    {
        String out = run(dev, mdType, "dump-md");
        // drop the header (time, host and command line)
        return Arrays.stream(out.split("\n")).filter(line -> !line.startsWith("#")).collect(Collectors.joining("\n"));
    }

    private String run(Path dev, String mdType, String... cmd) throws Exception
    {
        List<String> args = new ArrayList<>(
            Arrays.asList("--force", Integer.toString(MINOR), "v09", dev.toString(), mdType)
        );
        args.addAll(Arrays.asList(cmd));
        return drbdmeta(args);
    }

    private String drbdmeta(List<String> args) throws IOException, InterruptedException
    {
        List<String> cmd = new ArrayList<>();
        cmd.add(drbdmeta);
        cmd.addAll(args);
        Process proc = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("drbdmeta timed out", proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS));
        assertEquals(cmd + "\n" + out, 0, proc.exitValue());
        return out;
    }
}
