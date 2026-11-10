package com.linbit.linstor.api.rest.v1.config;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** fix-24b (E2): accounting of the REST response byte budget. */
public class ResponseBudgetTest
{
    private static final long MIB = ResponseBudget.MIB;

    @Test
    public void globalCapRefusesBeyondItAndReleaseRestores()
    {
        ResponseBudget budget = new ResponseBudget(10 * MIB, 100 * MIB, 100);
        ResponseBudget.Lease first = budget.tryAcquire("a", 4 * MIB);
        ResponseBudget.Lease second = budget.tryAcquire("b", 4 * MIB);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryAcquire("c", 4 * MIB));
        assertEquals(8 * MIB, budget.getGlobalUsedBytes());

        first.close();
        assertEquals(4 * MIB, budget.getGlobalUsedBytes());
        assertNotNull(budget.tryAcquire("c", 4 * MIB));
    }

    @Test
    public void perClientBytesAndCountAreSeparateFromOtherClients()
    {
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 10 * MIB, 2);
        assertNotNull(budget.tryAcquire("a", 6 * MIB));
        assertNull(budget.tryAcquire("a", 6 * MIB)); // bytes
        assertNotNull(budget.tryAcquire("a", 2 * MIB));
        assertNull(budget.tryAcquire("a", 1 * MIB)); // count
        assertNotNull(budget.tryAcquire("b", 6 * MIB));
        assertEquals(2, budget.getClientCount());
    }

    @Test
    public void closeIsIdempotentAndEmptyClientsAreForgotten()
    {
        ResponseBudget budget = new ResponseBudget(10 * MIB, 10 * MIB, 1);
        ResponseBudget.Lease lease = budget.tryAcquire("a", 3 * MIB);
        assertNotNull(lease);
        lease.close();
        lease.close();
        assertEquals(0, budget.getGlobalUsedBytes());
        assertEquals(0, budget.getClientCount());
        assertNotNull(budget.tryAcquire("a", 3 * MIB));
    }

    @Test
    public void leasesOlderThanTheMaxAgeAreReturnedOnTheNextAcquire()
    {
        AtomicLong now = new AtomicLong(0);
        ResponseBudget budget = new ResponseBudget(10 * MIB, 10 * MIB, 10, now::get);
        ResponseBudget.Lease stale = budget.tryAcquire("a", 8 * MIB);
        assertNotNull(stale);
        assertNull(budget.tryAcquire("b", 8 * MIB));

        now.set(ResponseBudget.LEASE_MAX_AGE_NANOS);
        ResponseBudget.Lease fresh = budget.tryAcquire("b", 8 * MIB);
        assertNotNull(fresh);
        assertEquals(1, budget.getExpiredCount());
        assertEquals(8 * MIB, budget.getGlobalUsedBytes());

        stale.close(); // late close of an expired lease must not release twice
        assertEquals(8 * MIB, budget.getGlobalUsedBytes());
    }

    @Test
    public void environmentValuesOutsideTheirRangeFallBackToTheDefault()
    {
        assertEquals(64 * MIB, ResponseBudget.mibOrDefault("64", -1));
        assertEquals(-1, ResponseBudget.mibOrDefault("0", -1));
        assertEquals(-1, ResponseBudget.mibOrDefault("x", -1));
        assertEquals(-1, ResponseBudget.mibOrDefault(null, -1));
        assertEquals(-1, ResponseBudget.mibOrDefault(Long.toString(ResponseBudget.MAX_MIB + 1), -1));
        assertEquals(4, ResponseBudget.longOrDefault(" 7 ", 1, 5, 4));
        assertEquals(3, ResponseBudget.longOrDefault("3", 1, 5, 4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void capsMustBePositive()
    {
        new ResponseBudget(0, 1, 1);
    }

    private static final String VIEW = "/v1/view/resources";

    @Test
    public void largeAnswerIsRefusedBeforeRenderingWhenTheClientHasItsCount()
    {
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 1000 * MIB, 2, 10);
        budget.recordAnswerSize("a", VIEW, 20 * MIB);
        assertNotNull(budget.tryAcquire("a", 20 * MIB));
        assertNotNull(budget.tryAcquire("a", 20 * MIB));
        assertNull(budget.tryAdmitRender("a", VIEW));
        // another client is not affected
        budget.recordAnswerSize("b", VIEW, 20 * MIB);
        assertNotNull(budget.tryAdmitRender("b", VIEW));
    }

    @Test
    public void largeAnswerIsRefusedWhenItsPredictedSizeWouldNotFitTheBytes()
    {
        // the rack case: 3 x 20 MB held under 64 MiB; a 4th 20-MB render is refused before it starts
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 64 * MIB, 4, 10);
        budget.recordAnswerSize("a", VIEW, 20 * MIB);
        for (int idx = 0; idx < 3; idx++)
        {
            assertNotNull(budget.tryAcquire("a", 20 * MIB));
        }
        assertNull(budget.tryAdmitRender("a", VIEW));
    }

    @Test
    public void largeAnswerIsRefusedWhenItsPredictedSizeWouldNotFitGlobally()
    {
        ResponseBudget budget = new ResponseBudget(30 * MIB, 1000 * MIB, 10, 10);
        budget.recordAnswerSize("a", VIEW, 20 * MIB);
        assertNotNull(budget.tryAcquire("b", 20 * MIB));
        assertNull(budget.tryAdmitRender("a", VIEW));
    }

    @Test
    public void predictedSmallAnswersAreOnlyLimitedByTheRendersCap()
    {
        // a client holding its full share of large answers can still render its small ones (e.g. a filtered list)
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 64 * MIB, 1, 2);
        assertNotNull(budget.tryAcquire("a", 60 * MIB));
        budget.recordAnswerSize("a", "/v1/view/resources?resources=rd1", 30_000);
        ResponseBudget.RenderTicket first = budget.tryAdmitRender("a", "/v1/view/resources?resources=rd1");
        ResponseBudget.RenderTicket unknown = budget.tryAdmitRender("a", "/v1/never-seen");
        assertNotNull(first);
        assertNotNull(unknown);
        assertNull(budget.tryAdmitRender("a", "/v1/never-seen")); // renders cap 2
        first.close();
        first.close(); // idempotent
        assertEquals(1, budget.getRendersInFlight("a"));
        unknown.close();
        assertEquals(0, budget.getRendersInFlight("a"));
    }

    @Test
    public void rememberedAnswerSizesAreBounded()
    {
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 64 * MIB, 1, 100);
        assertNotNull(budget.tryAcquire("a", 60 * MIB));
        budget.recordAnswerSize("a", VIEW, 20 * MIB);
        for (int idx = 0; idx < ResponseBudget.MAX_REMEMBERED_ANSWERS; idx++)
        {
            budget.recordAnswerSize("c", "/x" + idx, 1);
        }
        // the old entry was evicted: no prediction, so admitted (the filter's lease still applies after the render)
        ResponseBudget.RenderTicket ticket = budget.tryAdmitRender("a", VIEW);
        assertNotNull(ticket);
        ticket.close();
    }
}
