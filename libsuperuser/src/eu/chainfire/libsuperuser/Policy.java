/*
 * Copyright (C) 2012-2015 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.libsuperuser;

//TODO add detection for supolicy availability, or other su's equivalent tools

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for modifying SELinux policies, reducing the number of calls to a minimum.
 * <p/>
 * Example usage:
 * <p/>
 * <pre>
 * {@code
 *
 * private class Policy extends eu.chainfire.libsuperuser.Policy {
 *     @Override protected String[] getPolicies() {
 *         return new String[] {
 *             "allow sdcardd unlabeled dir { append create execute write relabelfrom link unlink ioctl getattr setattr read rename lock mounton quotaon swapon rmdir audit_access remove_name add_name reparent execmod search open }",
 *             "allow sdcardd unlabeled file { append create write relabelfrom link unlink ioctl getattr setattr read rename lock mounton quotaon swapon audit_access open }",
 *             "allow unlabeled unlabeled filesystem associate"
 *         };
 *     }
 * };
 * private Policy policy = new Policy();
 *
 * public void someFunctionNotCalledOnMainThread() {
 *     policy.inject();
 * }
 *
 * }
 * </pre>
 */
public abstract class Policy {
    /**
     * supolicy should be called as little as possible. We batch policies together. The command
     * line is guaranteed to be able to take 4096 characters. Reduce by a bit for supolicy itself.
     */
    private static final int MAX_POLICY_LENGTH = 4096 - 32;

    private static volatile boolean injected = false;

    /**
     * @return Have we injected our policies already?
     */
    public static boolean haveInjected() {
        return injected;
    }

    /**
     * Reset policies-have-been-injected state, if you really need to inject them again. Extremely
     * rare, you will probably never need this.
     */
    public static void resetInjected() {
        injected = false;
    }

    /**
     * Override this method to return a array of strings containing the policies you want to inject.
     *
     * @return Policies to inject
     */
    protected abstract String[] getPolicies();

    /**
     * Inject the policies defined by getPolicies(). Throws an exception if called from
     * the main thread in debug mode.
     */
    public void inject() {
        // No reason to bother if we're in permissive mode
        if (!Shell.SU.isSELinuxEnforcing()) return;

        // Been there, done that
        if (injected) return;

        // Retrieve policies
        String[] policies = getPolicies();
        if ((policies != null) && (policies.length > 0)) {
            List<String> commands = new ArrayList<String>();

            // Combine the policies into a minimal number of commands
            String command = "";
            for (String policy : policies) {
                if ((command.length() == 0) || (command.length() + policy.length() + 3 < MAX_POLICY_LENGTH)) {
                    command = command + " \"" + policy + "\"";
                } else {
                    commands.add("supolicy --live" + command);
                    command = "";
                }
            }
            if (command.length() > 0) {
                commands.add("supolicy --live" + command);
            }

            // Execute policies
            if (commands.size() > 0) {
                Shell.SU.run(commands);
            }
        }

        // We survived without throwing
        injected = true;
    }
}
