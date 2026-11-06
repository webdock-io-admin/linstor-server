package com.linbit.linstor.layer.storage.zfs.utils;

import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ZfsCommandsSnapshotOptionsTest
{
    @Test
    public void userPropOptionUsesTheLinstorPrefix()
    {
        assertThat(ZfsCommands.userPropOption("clone_for", "cpu-001"))
            .containsExactly("-o", "linstor:clone_for=cpu-001");
    }

    /**
     * The clone base snapshot and both of its marks are created by one "zfs snapshot" call, with the options before
     * the snapshot name.
     */
    @Test
    public void snapshotWithMarksIsOneCommand() throws Exception
    {
        ExtCmd extCmd = Mockito.mock(ExtCmd.class);
        when(extCmd.exec(any(String[].class))).thenReturn(new OutputData(new String[0], new byte[0], new byte[0], 0));

        List<String> options = new ArrayList<>();
        options.addAll(Arrays.asList(ZfsCommands.userPropOption("clone_for", "cpu-001")));
        options.addAll(Arrays.asList(ZfsCommands.userPropOption("marked_for_deletion", "true")));
        ZfsCommands.createSnapshotFullName(extCmd, "tank", "src_00000@CF_abc", options.toArray(new String[0]));

        verify(extCmd).exec(
            "zfs", "snapshot",
            "-o", "linstor:clone_for=cpu-001",
            "-o", "linstor:marked_for_deletion=true",
            "tank/src_00000@CF_abc"
        );
        Mockito.verifyNoMoreInteractions(extCmd);
    }
}
