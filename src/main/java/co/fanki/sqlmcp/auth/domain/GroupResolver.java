package co.fanki.sqlmcp.auth.domain;

import java.util.Set;

/**
 * Resolves the groups a user belongs to.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public interface GroupResolver {

    /**
     * Returns the groups of a user.
     *
     * <p>Business rule: a user that is unknown or suspended has no groups.
     *
     * @param email the user's email
     * @return the group emails, possibly empty
     */
    Set<String> groupsOf(String email);

}
