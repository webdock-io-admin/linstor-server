package com.linbit.linstor.api.rest.v1;

import com.linbit.linstor.api.LinStorScope;
import com.linbit.linstor.api.rest.v1.config.ResponseBudget;
import com.linbit.linstor.core.cfg.CtrlConfig;
import com.linbit.linstor.security.LdapAuthentication;
import com.linbit.linstor.testutils.EmptyErrorReporter;
import com.linbit.linstor.transaction.manager.TransactionMgrGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.http.server.Request;
import org.junit.Before;
import org.junit.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fix-24b (E3): offWorker(flux, request) admits the render against the shared budget once the data arrives; past the
 * client's renders cap the answer is refused at once; the ticket returns when the flux terminates or is cancelled.
 */
public class RequestHelperAdmitRenderTest
{
    private RequestHelper helper;
    private String client;

    @Before
    public void setUp()
    {
        helper = new RequestHelper(
            new EmptyErrorReporter(),
            mock(LinStorScope.class),
            mock(TransactionMgrGenerator.class),
            mock(LdapAuthentication.class),
            mock(CtrlConfig.class),
            Schedulers.immediate()
        );
        // unique address: the budget is the controller-wide shared one
        client = "test-" + UUID.randomUUID();
    }

    private Request request()
    {
        Request req = mock(Request.class);
        when(req.getRemoteAddr()).thenReturn(client);
        when(req.getRequestURI()).thenReturn("/v1/view/resources");
        return req;
    }

    @Test
    public void rendersBeyondTheCapAreRefusedAndTicketsReturnOnTermination()
    {
        ResponseBudget budget = ResponseBudget.shared();
        int cap = budget.getPerClientCapRenders();
        List<Disposable> inFlight = new ArrayList<>();
        try
        {
            // renders "in flight": the item arrived and was admitted, the flux has not terminated yet
            for (int idx = 0; idx < cap; idx++)
            {
                inFlight.add(helper.offWorker(Flux.just("data").concatWith(Flux.never()), request()).subscribe());
            }

            AtomicReference<Throwable> refused = new AtomicReference<>();
            helper.offWorker(Flux.just("data"), request()).subscribe(ignored -> { }, refused::set);
            assertTrue(
                "expected a refusal, got " + refused.get(),
                refused.get() instanceof RequestHelper.RenderRefusedException
            );

            // cancel one render: its ticket returns, and a new one is admitted (and returns on completion)
            inFlight.remove(0).dispose();
            assertEquals(List.of("data"), helper.offWorker(Flux.just("data"), request()).collectList().block());
            assertEquals(List.of("data"), helper.offWorker(Flux.just("data"), request()).collectList().block());
        }
        finally
        {
            inFlight.forEach(Disposable::dispose);
        }
    }
}
