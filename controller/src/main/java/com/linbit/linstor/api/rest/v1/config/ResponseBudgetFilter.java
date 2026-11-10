package com.linbit.linstor.api.rest.v1.config;

import com.linbit.linstor.annotation.Nullable;
import com.linbit.linstor.logging.ErrorReporter;

import javax.inject.Provider;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;

/**
 * fix-24b (E2, E2b): puts large REST answers under a {@link ResponseBudget} and writes them in chunks.
 *
 * E2: a String or byte[] entity larger than {@link #LARGE_ENTITY_BYTES} takes a lease for its encoded size. If the
 * global or the client's budget is exhausted, the body is dropped and the answer becomes 503 with Retry-After.
 *
 * E2b: the body is written in {@link #CHUNK_BYTES} pieces with a flush after each. Grizzly's OutputBuffer queues a
 * whole write before it checks whether the connection can take more, so a single 14 MB write to a client that does not
 * read ends up entirely in the async write queue. Per chunk, the flush blocks once the queue is full, and the
 * transport write timeout (fix-24 D2) ends the write of a stalled client after one chunk instead of after the body.
 *
 * The lease is returned when the write ends, also on IOException (client gone, write timeout).
 */
public class ResponseBudgetFilter implements ContainerResponseFilter
{
    static final int LARGE_ENTITY_BYTES = 1024 * 1024;
    static final int CHUNK_BYTES = 256 * 1024;
    static final String RETRY_AFTER_SEC = "5";
    static final String UNKNOWN_CLIENT = "unknown";
    /**
     * fix-24b (E2c): pending bytes per connection in Grizzly's async write queue before a write blocks. Grizzly's
     * default (AUTO = 4 x the socket's send buffer) can take a whole large answer, so the write would return at once
     * while the body sits in the queue outside the budget.
     */
    static final int MAX_PENDING_BYTES_PER_CONNECTION = 2 * CHUNK_BYTES;

    private final ResponseBudget budget;
    private final @Nullable ErrorReporter errorReporter;

    @Context
    private @Nullable Provider<Request> grizzlyRequestProvider;

    public ResponseBudgetFilter(ResponseBudget budgetRef, @Nullable ErrorReporter errorReporterRef)
    {
        budget = budgetRef;
        errorReporter = errorReporterRef;
    }

    @Override
    public void filter(ContainerRequestContext requestCtx, ContainerResponseContext responseCtx)
    {
        Object entity = responseCtx.getEntity();
        @Nullable byte[] body = null;
        if (entity instanceof String str)
        {
            // a String of fewer chars than the threshold can still encode to more bytes; check the encoded size
            if (str.length() > LARGE_ENTITY_BYTES / 4)
            {
                body = str.getBytes(StandardCharsets.UTF_8);
            }
        }
        else if (entity instanceof byte[] bytes)
        {
            body = bytes;
        }

        if (body != null && body.length > LARGE_ENTITY_BYTES)
        {
            String client = clientKey();
            ResponseBudget.@Nullable Lease lease = budget.tryAcquire(client, body.length);
            if (lease == null)
            {
                refuse(responseCtx, client, body.length);
            }
            else
            {
                MediaType mediaType = responseCtx.getMediaType();
                responseCtx.setEntity(new ChunkedBody(body, lease), null, mediaType);
                responseCtx.getHeaders().putSingle(HttpHeaders.CONTENT_LENGTH, body.length);
            }
        }
    }

    /** fix-24b (E2c): bound the async write queue of every listener of the server. Call before start(). */
    public static void limitPendingBytes(HttpServer server)
    {
        for (NetworkListener listener : server.getListeners())
        {
            listener.setMaxPendingBytes(MAX_PENDING_BYTES_PER_CONNECTION);
        }
    }

    private void refuse(ContainerResponseContext responseCtx, String client, long bytes)
    {
        if (errorReporter != null)
        {
            errorReporter.logWarning(
                "REST answer of %d bytes to %s refused: response budget exhausted (in use %d of %d bytes)",
                bytes,
                client,
                budget.getGlobalUsedBytes(),
                budget.getGlobalCapBytes()
            );
        }
        responseCtx.setStatus(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
        responseCtx.getHeaders().putSingle("Retry-After", RETRY_AFTER_SEC);
        responseCtx.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        responseCtx.setEntity(
            "[{\"ret_code\":-4611686018427387904,\"message\":\"Controller response budget exhausted, retry later\"}]",
            null,
            MediaType.APPLICATION_JSON_TYPE
        );
    }

    private String clientKey()
    {
        String key = UNKNOWN_CLIENT;
        try
        {
            if (grizzlyRequestProvider != null)
            {
                Request req = grizzlyRequestProvider.get();
                if (req != null && req.getRemoteAddr() != null)
                {
                    key = req.getRemoteAddr();
                }
            }
        }
        catch (RuntimeException ignored)
        {
            // no request in scope: all such answers share one client budget
        }
        return key;
    }

    /** Writes the body in chunks and returns the lease when the write ends. */
    static final class ChunkedBody implements StreamingOutput
    {
        private final byte[] body;
        private final ResponseBudget.Lease lease;

        ChunkedBody(byte[] bodyRef, ResponseBudget.Lease leaseRef)
        {
            body = bodyRef;
            lease = leaseRef;
        }

        @Override
        public void write(OutputStream out) throws IOException
        {
            try
            {
                for (int off = 0; off < body.length; off += CHUNK_BYTES)
                {
                    out.write(body, off, Math.min(CHUNK_BYTES, body.length - off));
                    out.flush();
                }
            }
            finally
            {
                lease.close();
            }
        }

        ResponseBudget.Lease getLease()
        {
            return lease;
        }
    }
}
