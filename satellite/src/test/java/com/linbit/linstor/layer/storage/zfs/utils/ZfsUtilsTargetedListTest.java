package com.linbit.linstor.layer.storage.zfs.utils;

import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.layer.storage.zfs.utils.ZfsUtils.ZfsInfo;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class ZfsUtilsTargetedListTest
{
    private static final String CLONE = "tank/clone_00000";
    private static final String ORIGIN = "tank/src_00000@CF_abc";
    private static final String CLONE_LINE = CLONE + "\t1024\t10737418240\tvolume\t16384\t" + ORIGIN + "\t-\n";
    private static final String ORIGIN_LINE = ORIGIN + "\t1024\t10737418240\tsnapshot\t-\t-\t" + CLONE + "\n";
    private static final String SRC = "tank/src_00000";
    // list of the clone, list of its origin, one get for both
    private static final int TARGETED_EXEC_COUNT = 3;
    private static final String SRC_LINE = SRC + "\t1024\t10737418240\tvolume\t16384\t-\t-\n";

    private final List<List<String>> commands = new ArrayList<>();
    private ExtCmdFactory extCmdFactory;
    private final Map<String, BiConsumer<ZfsInfo, String>> markedSetter = new HashMap<>();

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
                StringBuilder out = new StringBuilder();
                if (cmd.get(1).equals("list"))
                {
                    if (cmd.contains(CLONE))
                    {
                        out.append(CLONE_LINE);
                    }
                    if (cmd.contains(SRC))
                    {
                        out.append(SRC_LINE);
                        if (cmd.contains("-r"))
                        {
                            out.append(ORIGIN_LINE);
                        }
                    }
                    if (cmd.contains(ORIGIN))
                    {
                        out.append(ORIGIN_LINE);
                    }
                }
                else
                {
                    if (cmd.contains(ORIGIN) || (cmd.contains("-r") && cmd.contains(SRC)))
                    {
                        out.append(ORIGIN).append("\ttrue\n");
                    }
                }
                return new OutputData(
                    cmd.toArray(new String[0]),
                    out.toString().getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    0
                );
            }
        );
        extCmdFactory = Mockito.mock(ExtCmdFactory.class);
        when(extCmdFactory.create()).thenReturn(extCmd);
        markedSetter.put(
            "marked_for_deletion",
            (zfsInfo, prop) -> zfsInfo.markedForDeletion = Boolean.parseBoolean(prop)
        );
    }

    @Test
    public void targetedListUsesNoRecursionAndAddsTheOrigin() throws Exception
    {
        HashMap<String, ZfsInfo> infos = ZfsUtils.getZfsList(
            extCmdFactory,
            Collections.singleton(CLONE),
            DeviceProviderKind.ZFS_THIN,
            markedSetter,
            false
        );

        assertThat(commands).allSatisfy(cmd -> assertThat(cmd).doesNotContain("-r"));
        assertThat(commands).hasSize(TARGETED_EXEC_COUNT);
        assertThat(infos).containsOnlyKeys(CLONE, ORIGIN);
        assertThat(infos.get(CLONE).snapshotsComplete).isFalse();
        assertThat(infos.get(ORIGIN).markedForDeletion).isTrue();
        assertThat(infos.get(ORIGIN).clones).containsExactly(CLONE);
    }

    @Test
    public void recursiveListMarksVolumesComplete() throws Exception
    {
        HashMap<String, ZfsInfo> infos = ZfsUtils.getZfsList(
            extCmdFactory,
            Collections.singleton(SRC),
            DeviceProviderKind.ZFS_THIN,
            markedSetter,
            true
        );

        assertThat(commands).allSatisfy(cmd -> assertThat(cmd).contains("-r"));
        assertThat(infos.get(SRC).snapshotsComplete).isTrue();
        assertThat(infos.get(SRC).snapshots).extracting(info -> info.poolName + "/" + info.identifier)
            .containsExactly(ORIGIN);
        assertThat(infos.get(ORIGIN).markedForDeletion).isTrue();
    }

    @Test
    public void defaultOverloadStaysRecursive() throws Exception
    {
        ZfsUtils.getZfsList(extCmdFactory, Collections.singleton(SRC), DeviceProviderKind.ZFS_THIN, markedSetter);

        assertThat(commands).isNotEmpty().allSatisfy(cmd -> assertThat(cmd).contains("-r"));
    }

    /**
     * The allocated-size query (behind /v1/view/resources) lists only the named volumes: one "zfs list" without
     * "-r", no origin lookup and no "zfs get", however many snapshots the volumes have.
     */
    @Test
    public void listDatasetsListsOnlyTheNamedVolumes() throws Exception
    {
        HashMap<String, ZfsInfo> infos = ZfsUtils.listDatasets(
            extCmdFactory,
            Arrays.asList(SRC, CLONE),
            DeviceProviderKind.ZFS_THIN
        );

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0)).contains("list").doesNotContain("-r").contains(SRC, CLONE);
        assertThat(infos).containsOnlyKeys(SRC, CLONE);
        assertThat(infos.get(SRC).snapshots).isEmpty();
        assertThat(infos.get(CLONE).allocatedSize).isEqualTo(1L);
    }

    @Test
    public void vanishedOriginIsSkipped() throws Exception
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
                boolean listClone = cmd.get(1).equals("list") && cmd.contains(CLONE);
                // the origin snapshot was destroyed after the first list: zfs prints nothing for it and exits with 1
                return new OutputData(
                    cmd.toArray(new String[0]),
                    (listClone ? CLONE_LINE : "").getBytes(StandardCharsets.UTF_8),
                    (listClone ? "" : "cannot open '" + ORIGIN + "': dataset does not exist\n")
                        .getBytes(StandardCharsets.UTF_8),
                    listClone ? 0 : 1
                );
            }
        );
        when(extCmdFactory.create()).thenReturn(extCmd);

        HashMap<String, ZfsInfo> infos = ZfsUtils.getZfsList(
            extCmdFactory,
            Collections.singleton(CLONE),
            DeviceProviderKind.ZFS_THIN,
            markedSetter,
            false
        );

        assertThat(infos).containsOnlyKeys(CLONE);
    }
}
