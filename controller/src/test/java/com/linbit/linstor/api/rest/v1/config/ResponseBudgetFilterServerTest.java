package com.linbit.linstor.api.rest.v1.config;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * fix-24b (E2, E2b) against a real Grizzly + Jersey server: clients that send a request and never read can hold at
 * most the budget, answers beyond it get 503, and a stalled write returns its lease at the transport write timeout.
 */
public class ResponseBudgetFilterServerTest
{
    private static final int BODY_MIB = 4;
    private static final long MIB = ResponseBudget.MIB;
    private static final int WRITE_TIMEOUT_SEC = 2;
    private static final String BODY;

    static
    {
        char[] chars = new char[BODY_MIB * 1024 * 1024];
        Arrays.fill(chars, 'x');
        BODY = new String(chars);
    }

    @Path("big")
    public static class BigResource
    {
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public String big()
        {
            return BODY;
        }
    }

    private HttpServer server;
    private final List<Socket> sockets = new ArrayList<>();

    @After
    public void tearDown() throws IOException
    {
        for (Socket sock : sockets)
        {
            sock.close();
        }
        if (server != null)
        {
            server.shutdownNow();
        }
    }

    private int start(ResponseBudget budget) throws IOException
    {
        int port;
        try (ServerSocket probe = new ServerSocket(0))
        {
            port = probe.getLocalPort();
        }
        ResourceConfig cfg = new ResourceConfig(BigResource.class);
        cfg.register(new ResponseBudgetFilter(budget, null));
        server = GrizzlyHttpServerFactory.createHttpServer(URI.create("http://127.0.0.1:" + port + "/"), cfg, false);
        for (NetworkListener listener : server.getListeners())
        {
            listener.getTransport().setWriteTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS);
        }
        ResponseBudgetFilter.limitPendingBytes(server);
        server.start();
        return port;
    }

    /** Sends the request, reads only the status line, and then never reads again. */
    private String requestAndStall(int port) throws IOException
    {
        Socket sock = new Socket();
        sockets.add(sock);
        sock.setReceiveBufferSize(4096);
        sock.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
        sock.setSoTimeout(10_000);
        sock.getOutputStream().write(
            "GET /big HTTP/1.1\r\nHost: test\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
        );
        sock.getOutputStream().flush();
        InputStream in = sock.getInputStream();
        StringBuilder line = new StringBuilder();
        int chr;
        while ((chr = in.read()) != -1 && chr != '\n')
        {
            line.append((char) chr);
        }
        return line.toString().trim();
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException
    {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end)
        {
            Thread.sleep(50);
        }
    }

    @Test
    public void nonReadingClientsHoldAtMostTheGlobalBudget() throws Exception
    {
        // room for two 4-MiB answers, per-client limits out of the way
        ResponseBudget budget = new ResponseBudget(9 * MIB, 1000 * MIB, 1000);
        int port = start(budget);

        int ok = 0;
        int refused = 0;
        for (int idx = 0; idx < 6; idx++)
        {
            String status = requestAndStall(port);
            if (status.startsWith("HTTP/1.1 200"))
            {
                ok++;
            }
            else if (status.startsWith("HTTP/1.1 503"))
            {
                refused++;
            }
            assertTrue("budget exceeded: " + budget.getGlobalUsedBytes(), budget.getGlobalUsedBytes() <= 9 * MIB);
        }
        assertEquals(2, ok);
        assertEquals(4, refused);

        // E2b: the stalled writes still hold their leases (the body is not queued in one piece)
        Thread.sleep(500);
        assertEquals(2 * BODY_MIB * MIB, budget.getGlobalUsedBytes());
        assertEquals(java.util.Set.of("127.0.0.1"), budget.getClientKeys());

        // the transport write timeout ends the stalled writes and returns the leases
        waitFor(() -> budget.getGlobalUsedBytes() == 0, (WRITE_TIMEOUT_SEC + 8) * 1000L);
        assertEquals(0, budget.getGlobalUsedBytes());
        assertEquals("HTTP/1.1 200 OK", requestAndStall(port));
    }

    @Test
    public void perClientCountLimitsOneClient() throws Exception
    {
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 1000 * MIB, 1);
        int port = start(budget);
        assertEquals("HTTP/1.1 200 OK", requestAndStall(port));
        assertTrue(requestAndStall(port).startsWith("HTTP/1.1 503"));
    }

    @Test
    public void closedClientReturnsItsLease() throws Exception
    {
        ResponseBudget budget = new ResponseBudget(1000 * MIB, 1000 * MIB, 1000);
        int port = start(budget);
        assertEquals("HTTP/1.1 200 OK", requestAndStall(port));
        assertEquals(BODY_MIB * MIB, budget.getGlobalUsedBytes());
        sockets.get(0).close();
        waitFor(() -> budget.getGlobalUsedBytes() == 0, (WRITE_TIMEOUT_SEC + 8) * 1000L);
        assertEquals(0, budget.getGlobalUsedBytes());
    }
}
