package com.linbit.linstor.layer.storage.zfs.utils;

import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.linstor.layer.storage.zfs.utils.ZfsCommands.ZfsVolumeType;
import com.linbit.linstor.storage.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ZfsCommandsDestroyRetryTest
{
    private static final String FQ_ID = "tank/rsc_00000";
    private static final String[] DESTROY_CMD = {"zfs", "destroy", FQ_ID};
    private static final OutputData OK = result(0, "");
    private static final OutputData BUSY = result(1, "cannot destroy '" + FQ_ID + "': dataset is busy\n");
    private static final int MAX_EXECS = ZfsCommands.DESTROY_RETRY_COUNT + 1;

    private final List<Long> sleeps = new ArrayList<>();
    private ExtCmd extCmd;

    private static OutputData result(int exitCode, String stdErr)
    {
        return new OutputData(DESTROY_CMD, new byte[0], stdErr.getBytes(StandardCharsets.UTF_8), exitCode);
    }

    /** returns <code>busyCount</code> times BUSY, followed by <code>last</code> */
    private void execReturns(int busyCount, OutputData last) throws Exception
    {
        OutputData[] rest = new OutputData[busyCount];
        Arrays.fill(rest, BUSY);
        rest[busyCount - 1] = last;
        when(extCmd.exec(any(String[].class))).thenReturn(BUSY, rest);
    }

    private OutputData delete() throws StorageException
    {
        return ZfsCommands.delete(
            extCmd,
            FQ_ID,
            ZfsVolumeType.VOLUME,
            ZfsCommands.destroyRetryHandler(sleeps::add)
        );
    }

    @Before
    public void setUp()
    {
        extCmd = Mockito.mock(ExtCmd.class);
    }

    @Test
    public void destroySucceedsOnLastRetry() throws Exception
    {
        // busy for longer than the former 3 retries at 100ms
        execReturns(ZfsCommands.DESTROY_RETRY_COUNT, OK);

        assertThat(delete().exitCode).isEqualTo(0);
        verify(extCmd, times(MAX_EXECS)).exec(DESTROY_CMD);
        assertThat(sleeps).containsExactly(200L, 400L, 800L, 1_600L, 2_000L, 2_000L);
    }

    @Test
    public void destroyFailsWhenBusyBeyondRetries() throws Exception
    {
        execReturns(ZfsCommands.DESTROY_RETRY_COUNT, BUSY);

        assertThatThrownBy(this::delete).isInstanceOf(StorageException.class);
        verify(extCmd, times(MAX_EXECS)).exec(DESTROY_CMD);
    }

    @Test
    public void destroyNotRetriedOnOtherErrors() throws Exception
    {
        when(extCmd.exec(any(String[].class)))
            .thenReturn(result(1, "cannot open '" + FQ_ID + "': dataset does not exist\n"));

        assertThatThrownBy(this::delete).isInstanceOf(StorageException.class);
        verify(extCmd, times(1)).exec(DESTROY_CMD);
        assertThat(sleeps).isEmpty();
    }
}
