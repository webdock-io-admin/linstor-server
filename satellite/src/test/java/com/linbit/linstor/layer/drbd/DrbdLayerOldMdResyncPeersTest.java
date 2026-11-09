package com.linbit.linstor.layer.drbd;

import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.types.NodeId;
import com.linbit.linstor.stateflags.StateFlags;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdRscData;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdRscDfnData;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class DrbdLayerOldMdResyncPeersTest
{
    private final List<DrbdRscData<Resource>> all = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private DrbdRscData<Resource> rsc(int nodeId, boolean diskless, boolean deleting) throws Exception
    {
        Resource res = Mockito.mock(Resource.class);
        when(res.isDrbdDiskless()).thenReturn(diskless);
        StateFlags<Resource.Flags> flags = Mockito.mock(StateFlags.class);
        when(flags.isUnset(any(), any())).thenReturn(!deleting);
        when(res.getStateFlags()).thenReturn(flags);

        DrbdRscData<Resource> data = Mockito.mock(DrbdRscData.class);
        when(data.getNodeId()).thenReturn(new NodeId(nodeId));
        when(data.getAbsResource()).thenReturn(res);
        all.add(data);
        return data;
    }

    @SuppressWarnings("unchecked")
    private void linkAll()
    {
        DrbdRscDfnData<Resource> dfn = Mockito.mock(DrbdRscDfnData.class);
        when(dfn.getDrbdRscDataList()).thenReturn(all);
        for (DrbdRscData<Resource> data : all)
        {
            when(data.getRscDfnLayerObject()).thenReturn(dfn);
        }
    }

    /** Only diskful, not deleting peers with a higher node-id: one side of each pair verifies. */
    @Test
    public void onlyHigherDiskfulPeers() throws Exception
    {
        DrbdRscData<Resource> local = rsc(1, false, false);
        rsc(0, false, false);   // lower node-id: that node verifies against us
        rsc(2, false, false);   // diskful, higher: verify
        rsc(3, true, false);    // diskless: never
        rsc(4, false, true);    // being deleted: never
        rsc(5, false, false);   // diskful, higher: verify
        linkAll();

        assertThat(DrbdLayer.oldMdResyncPeers(local)).containsExactly(2, 5);
    }

    @Test
    public void highestNodeIdVerifiesNothing() throws Exception
    {
        rsc(0, false, false);
        DrbdRscData<Resource> local = rsc(1, false, false);
        linkAll();

        assertThat(DrbdLayer.oldMdResyncPeers(local)).isEmpty();
    }
}
