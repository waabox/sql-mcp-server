package co.fanki.sqlmcp.auth.domain;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Binds the {@link Caller} to the thread that runs a tool call.
 *
 * <p>Business rule: when nothing is bound (STDIO transport) the caller is {@link Caller#local()}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class CurrentCaller {

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private CurrentCaller() {
    }

    /**
     * Returns the caller bound to the current thread.
     *
     * @return the bound caller, or {@link Caller#local()} when none is bound
     */
    public static Caller get() {
        final Caller caller = CURRENT.get();
        return caller != null ? caller : Caller.local();
    }

    /**
     * Runs an action with the caller bound to the current thread.
     *
     * <p>Business rule: the previous binding is always restored, even when the action throws.
     *
     * @param caller the caller to bind; must not be null
     * @param action the action to run; must not be null
     * @param <T> the result type
     * @return the result of the action
     */
    public static <T> T runAs(final Caller caller, final Supplier<T> action) {
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(action, "action must not be null");
        final Caller previous = CURRENT.get();
        CURRENT.set(caller);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

}
