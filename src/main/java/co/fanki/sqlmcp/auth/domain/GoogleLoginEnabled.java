package co.fanki.sqlmcp.auth.domain;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when Google login is enabled.
 *
 * <p>Business rule: the value of {@code sql-mcp.auth.google.enabled} is bound with the same relaxed boolean rules
 * as {@link AuthSettings} ({@code true}, {@code on}, {@code yes}, in any case), so the filter, the settings and
 * every Google bean and controller always agree on whether Google login is enabled.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class GoogleLoginEnabled implements Condition {

    private static final String PROPERTY = "sql-mcp.auth.google.enabled";

    /**
     * Evaluates the condition.
     *
     * @param context the condition context
     * @param metadata the metadata of the annotated element
     * @return true when Google login is enabled
     */
    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment()).bind(PROPERTY, Boolean.class).orElse(false);
    }

}
