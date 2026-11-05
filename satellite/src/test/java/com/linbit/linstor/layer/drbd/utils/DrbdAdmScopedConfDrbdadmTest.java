package com.linbit.linstor.layer.drbd.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs a real drbdadm (path in the environment variable LINSTOR_TEST_DRBDADM; skipped if unset) on sample LINSTOR
 * resource files and checks that a scoped config parses each resource exactly like the full config does: same
 * "dump", and the same drbdsetup command list from a dry-run "up" (-d), which includes the merged common options.
 */
public class DrbdAdmScopedConfDrbdadmTest
{
    private static final String DRBDADM_ENV = "LINSTOR_TEST_DRBDADM";
    private static final List<String> SAMPLE_RSCS = Arrays.asList("rsc1", "rsc2", "rsc3");
    private static final long DRBDADM_TIMEOUT_SEC = 30;

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private String drbdadm;
    private Path samples;

    @Before
    public void setUp() throws URISyntaxException
    {
        drbdadm = System.getenv(DRBDADM_ENV);
        Assume.assumeTrue(DRBDADM_ENV + " not set", drbdadm != null && Files.isExecutable(Paths.get(drbdadm)));
        samples = Paths.get(getClass().getResource("/drbdadm-scoped/rsc1.res").toURI()).getParent();
    }

    @Test
    public void scopedDumpEqualsFullDump() throws Exception
    {
        Path globalCommon = samples.resolve("global_common.conf");
        Path fullConf = writeConf(
            "full.conf",
            "include \"" + globalCommon + "\";\ninclude \"" + samples + "/*.res\";\n"
        );
        for (String rsc : SAMPLE_RSCS)
        {
            Path scopedConf = writeConf(
                rsc + "-scoped.conf",
                DrbdAdmScopedConf.buildContent(globalCommon, Collections.singletonList(samples.resolve(rsc + ".res")))
            );
            String full = drbdadm(fullConf, "dump", rsc);
            assertTrue(full, full.contains("resource " + rsc + " {"));
            assertEquals(rsc, full, drbdadm(scopedConf, "dump", rsc));

            String fullUp = drbdadm(fullConf, "-d", "up", rsc);
            assertTrue(fullUp, fullUp.contains("drbdsetup new-resource " + rsc));
            assertEquals(rsc, fullUp, drbdadm(scopedConf, "-d", "up", rsc));
        }
    }

    @Test
    public void scopedConfKeepsTheCommonSection() throws Exception
    {
        Path globalCommon = writeConf(
            "global_common.conf",
            "global { usage-count no; }\ncommon { options { auto-promote no; } }\n"
        );
        Path scopedConf = writeConf(
            "scoped.conf",
            DrbdAdmScopedConf.buildContent(globalCommon, Collections.singletonList(samples.resolve("rsc1.res")))
        );
        String up = drbdadm(scopedConf, "-d", "up", "rsc1");
        assertTrue(up, up.contains("drbdsetup new-resource rsc1 0 --auto-promote=no"));
    }

    private Path writeConf(String name, String content) throws IOException
    {
        Path conf = tmpDir.getRoot().toPath().resolve(name);
        Files.write(conf, content.getBytes(StandardCharsets.UTF_8));
        return conf;
    }

    private String drbdadm(Path conf, String... args) throws IOException, InterruptedException
    {
        List<String> cmd = new ArrayList<>(Arrays.asList(drbdadm, "-c", conf.toString()));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("__DRBD_NODE__", "nodeA");
        pb.environment().put("DRBD_DRIVER_VERSION_OVERRIDE", "9.3.4");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("drbdadm timed out", proc.waitFor(DRBDADM_TIMEOUT_SEC, TimeUnit.SECONDS));
        assertEquals(out, 0, proc.exitValue());
        // the dump names the file a resource was defined in; that line differs by construction
        return out.replaceAll("(?m)^# defined at .*$", "");
    }
}
