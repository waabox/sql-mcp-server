package co.fanki.sqlmcp.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Input stream that signals when the underlying stream reaches end of file.
 *
 * <p>Used for the STDIO transport: when the MCP client closes the server's
 * standard input, the server must shut down instead of lingering with open
 * database connections.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class EofAwareInputStream extends FilterInputStream {

    private final CountDownLatch eof = new CountDownLatch(1);

    private EofAwareInputStream(final InputStream in) {
        super(in);
    }

    /**
     * Wraps a stream.
     *
     * @param in the stream to wrap, never null
     * @return the wrapping stream
     */
    public static EofAwareInputStream wrap(final InputStream in) {
        return new EofAwareInputStream(java.util.Objects.requireNonNull(in, "in must not be null"));
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value < 0) {
            eof.countDown();
        }
        return value;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count < 0) {
            eof.countDown();
        }
        return count;
    }

    /**
     * Blocks until the end of the stream is reached.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void awaitEof() throws InterruptedException {
        eof.await();
    }

}
