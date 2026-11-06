package com.linbit.linstor.layer.storage.zfs;

import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.storage.data.provider.zfs.ZfsData;

import org.junit.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ZfsProviderCloseForCloneTest
{
    /**
     * A "zfs clone" never opens the devices (no clone device path), so closing must not run "zfs set snapdev=hidden".
     * The provider is a mock calling the real method with no dependencies injected: any zfs command would throw.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void zfsCloneWithoutOpenedDevicesRunsNoCommand() throws Exception
    {
        ZfsProvider provider = Mockito.mock(ZfsProvider.class, Mockito.CALLS_REAL_METHODS);
        ZfsData<Resource> vlm = Mockito.mock(ZfsData.class);
        when(vlm.getCloneDevicePath()).thenReturn(null);

        provider.closeForClone(vlm, "cpu-001");

        verify(vlm).setCloneDevicePath(null);
        verify(vlm, Mockito.never()).getStorPool();
    }

    /**
     * With an opened device (clone device path set) the hide still runs. Without injected dependencies it fails when
     * creating the zfs command (null ExtCmdFactory), which shows the branch is taken.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void openedDeviceIsStillHidden()
    {
        ZfsProvider provider = Mockito.mock(ZfsProvider.class, Mockito.CALLS_REAL_METHODS);
        ZfsData<Resource> vlm = Mockito.mock(ZfsData.class);
        when(vlm.getCloneDevicePath()).thenReturn("/dev/zvol/tank/src_00000@CF_abc");

        assertThatThrownBy(() -> provider.closeForClone(vlm, "cpu-001")).isInstanceOf(NullPointerException.class);
        verify(vlm, Mockito.never()).setCloneDevicePath(null);
    }
}
