package com.linbit.linstor.api.rest.v1;

import java.util.concurrent.RejectedExecutionException;

import org.junit.Test;
import reactor.core.Exceptions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** fix-24 (D): a rejected publishOn (RestResponse queue full) is recognised, also when wrapped. */
public class RequestHelperRejectTest
{
    @Test
    public void rejectedIsRecognised()
    {
        assertTrue(RequestHelper.isRejected(new RejectedExecutionException("full")));
        assertTrue(RequestHelper.isRejected(Exceptions.failWithRejected()));
        assertTrue(RequestHelper.isRejected(new RuntimeException(new RejectedExecutionException("wrapped"))));
        assertFalse(RequestHelper.isRejected(new IllegalStateException("other")));
        assertFalse(RequestHelper.isRejected(null));
    }
}
