/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
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

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.lang.Object;
import java.lang.String;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import eu.chainfire.libsuperuser.StreamGobbler.OnLineListener;
import eu.chainfire.libsuperuser.StreamGobbler.OnStreamClosedListener;

/**
 * Class providing functionality to execute commands in a (root) shell
 */
@SuppressWarnings({"WeakerAccess", "UnusedReturnValue", "unused", "StatementWithEmptyBody", "DeprecatedIsStillUsed", "deprecation"})
public class Shell {
    /**
     * Exception class used to crash application when shell commands are executed
     * from the main thread, and we are in debug mode.
     */
    @SuppressWarnings({"serial", "WeakerAccess"})
    public static class ShellOnMainThreadException extends RuntimeException {
        public static final String EXCEPTION_COMMAND = "Application attempted to run a shell command from the main thread";
        public static final String EXCEPTION_NOT_IDLE = "Application attempted to wait for a non-idle shell to close on the main thread";
        public static final String EXCEPTION_WAIT_IDLE = "Application attempted to wait for a shell to become idle on the main thread";
        public static final String EXCEPTION_TOOLBOX = "Application attempted to init the Toolbox class from the main thread";

        public ShellOnMainThreadException(String message) {
            super(message);
        }
    }

    /**
     * Exception class used to notify developer that a shell was not close()d
     */
    @SuppressWarnings({"serial", "WeakerAccess"})
    public static class ShellNotClosedException extends RuntimeException {
        public static final String EXCEPTION_NOT_CLOSED = "Application did not close() interactive shell";

        public ShellNotClosedException() {
            super(EXCEPTION_NOT_CLOSED);
        }
    }

    /**
     * Exception class used to notify developer that a shell was not close()d
     */
    @SuppressWarnings({"serial", "WeakerAccess"})
    public static class ShellDiedException extends Exception {
        public static final String EXCEPTION_SHELL_DIED = "Shell died (or access was not granted)";

        public ShellDiedException() {
            super(EXCEPTION_SHELL_DIED);
        }
    }

    private static volatile boolean redirectDeprecated = true;

    /**
     * @see #setRedirectDeprecated(boolean)
     *
     * @return Whether deprecated calls are automatically redirected to {@link PoolWrapper}
     */
    public static boolean isRedirectDeprecated() {
        return redirectDeprecated;
    }

    /**
     * Set whether deprecated calls (such as Shell.run, Shell.SH/SU.run, etc) should automatically
     * redirect to Shell.Pool.?.run(). This is true by default, but it is possible to disable this
     * behavior for backwards compatibility
     *
     * @param redirectDeprecated Whether deprecated calls should be automatically redirected to {@link PoolWrapper} (default true)
     */
    public static void setRedirectDeprecated(boolean redirectDeprecated) {
        Shell.redirectDeprecated = redirectDeprecated;
    }

    /**
     * <p>
     * Runs commands using the supplied shell, and returns the output, or null
     * in case of errors.
     * </p>
     *
     * @deprecated This method is deprecated and only provided for backwards
     * compatibility. Use {@link Pool}'s method instead. If {@link #isRedirectDeprecated()}
     * is true (default), these calls are now automatically redirected.
     *
     * @param shell The shell to use for executing the commands
     * @param commands The commands to execute
     * @param wantSTDERR Return STDERR in the output ?
     * @return Output of the commands, or null in case of an error
     */
    @Nullable
    @Deprecated
    @WorkerThread
    public static List<String> run(@NonNull String shell, @NonNull String[] commands, boolean wantSTDERR) {
        return run(shell, commands, null, wantSTDERR);
    }

    /**
     * <p>
     * Runs commands using the supplied shell, and returns the output, or null
     * in case of errors.
     * </p>
     * <p>
     * Note that due to compatibility with older Android versions, wantSTDERR is
     * not implemented using redirectErrorStream, but rather appended to the
     * output. STDOUT and STDERR are thus not guaranteed to be in the correct
     * order in the output.
     * </p>
     * <p>
     * Note as well that this code will intentionally crash when run in debug
     * mode from the main thread of the application. You should always execute
     * shell commands from a background thread.
     * </p>
     * <p>
     * When in debug mode, the code will also excessively log the commands
     * passed to and the output returned from the shell.
     * </p>
     * <p>
     * Though this function uses background threads to gobble STDOUT and STDERR
     * so a deadlock does not occur if the shell produces massive output, the
     * output is still stored in a List&lt;String&gt;, and as such doing
     * something like <em>'ls -lR /'</em> will probably have you run out of
     * memory.
     * </p>
     *
     * @deprecated This method is deprecated and only provided for backwards
     * compatibility. Use {@link Pool}'s method instead. If {@link #isRedirectDeprecated()}
     * is true (default), these calls are now automatically redirected.
     *
     * @param shell The shell to use for executing the commands
     * @param commands The commands to execute
     * @param environment List of all environment variables (in 'key=value' format) or null for defaults
     * @param wantSTDERR  Return STDERR in the output ?
     * @return Output of the commands, or null in case of an error
     */
    @Nullable
    @Deprecated
    @WorkerThread
    public static List<String> run(@NonNull String shell, @NonNull String[] commands, @Nullable String[] environment,
                                   boolean wantSTDERR) {
        String shellUpper = shell.toUpperCase(Locale.ENGLISH);

        if (Debug.getSanityChecksEnabledEffective() && Debug.onMainThread()) {
            // check if we're running in the main thread, and if so, crash if
            // we're in debug mode, to let the developer know attention is
            // needed here.

            Debug.log(ShellOnMainThreadException.EXCEPTION_COMMAND);
            throw new ShellOnMainThreadException(ShellOnMainThreadException.EXCEPTION_COMMAND);
        }

        if (redirectDeprecated) {
            // use our Threaded pool implementation instead
            return Pool.getWrapper(shell).run(commands, environment, wantSTDERR);
        }

        Debug.logCommand(String.format(Locale.ENGLISH, "[%s%%] START", shellUpper));

        List<String> res = Collections.synchronizedList(new ArrayList<String>());

        try {
            // Combine passed environment with system environment
            if (environment != null) {
                Map<String, String> newEnvironment = new HashMap<String, String>(System.getenv());
                int split;
                for (String entry : environment) {
                    if ((split = entry.indexOf("=")) >= 0) {
                        newEnvironment.put(entry.substring(0, split), entry.substring(split + 1));
                    }
                }
                int i = 0;
                environment = new String[newEnvironment.size()];
                for (Map.Entry<String, String> entry : newEnvironment.entrySet()) {
                    environment[i] = entry.getKey() + "=" + entry.getValue();
                    i++;
                }
            }

            // setup our process, retrieve STDIN stream, and STDOUT/STDERR
            // gobblers
            Process process = Runtime.getRuntime().exec(shell, environment);
            DataOutputStream STDIN = new DataOutputStream(process.getOutputStream());
            StreamGobbler STDOUT = new StreamGobbler(shellUpper + "-", process.getInputStream(),
                    res);
            StreamGobbler STDERR = new StreamGobbler(shellUpper + "*", process.getErrorStream(),
                    wantSTDERR ? res : null);

            // start gobbling and write our commands to the shell
            STDOUT.start();
            STDERR.start();
            try {
                for (String write : commands) {
                    Debug.logCommand(String.format(Locale.ENGLISH, "[%s+] %s", shellUpper, write));
                    STDIN.write((write + "\n").getBytes("UTF-8"));
                    STDIN.flush();
                }
                STDIN.write("exit\n".getBytes("UTF-8"));
                STDIN.flush();
            } catch (IOException e) {
                if (e.getMessage().contains("EPIPE") || e.getMessage().contains("Stream closed")) {
                    // Method most horrid to catch broken pipe, in which case we
                    // do nothing. The command is not a shell, the shell closed
                    // STDIN, the script already contained the exit command, etc.
                    // these cases we want the output instead of returning null.
                } else {
                    // other issues we don't know how to handle, leads to
                    // returning null
                    throw e;
                }
            }

            // wait for our process to finish, while we gobble away in the
            // background
            process.waitFor();

            // make sure our threads are done gobbling, our streams are closed,
            // and the process is destroyed - while the latter two shouldn't be
            // needed in theory, and may even produce warnings, in "normal" Java
            // they are required for guaranteed cleanup of resources, so lets be
            // safe and do this on Android as well
            try {
                STDIN.close();
            } catch (IOException e) {
                // might be closed already
            }
            STDOUT.join();
            STDERR.join();
            process.destroy();

            // in case of su, 255 usually indicates access denied
            if (SU.isSU(shell) && (process.exitValue() == 255)) {
                res = null;
            }
        } catch (IOException e) {
            // shell probably not found
            res = null;
        } catch (InterruptedException e) {
            // this should really be re-thrown
            res = null;
        }

        Debug.logCommand(String.format(Locale.ENGLISH, "[%s%%] END", shell.toUpperCase(Locale.ENGLISH)));
        return res;
    }

    protected static final String[] availableTestCommands = new String[]{
            "echo -BOC-",
            "id"
    };

    /**
     * See if the shell is alive, and if so, check the UID
     *
     * @param ret Standard output from running availableTestCommands
     * @param checkForRoot true if we are expecting this shell to be running as root
     * @return true on success, false on error
     */
    protected static boolean parseAvailableResult(@Nullable List<String> ret, boolean checkForRoot) {
        if (ret == null)
            return false;

        // this is only one of many ways this can be done
        boolean echo_seen = false;

        for (String line : ret) {
            if (line.contains("uid=")) {
                // id command is working, let's see if we are actually root
                return !checkForRoot || line.contains("uid=0");
            } else if (line.contains("-BOC-")) {
                // if we end up here, at least the su command starts some kind
                // of shell, let's hope it has root privileges - no way to know without
                // additional native binaries
                echo_seen = true;
            }
        }

        return echo_seen;
    }

    /**
     * This class provides utility functions to easily execute commands using SH
     */
    public static class SH {
        /**
         * Runs command and return output
         *
         * @deprecated Consider using Shell.Pool.SH.run() instead
         *
         * @param command The command to run
         * @return Output of the command, or null in case of an error
         */
        @Nullable
        @Deprecated
        @WorkerThread
        public static List<String> run(@NonNull String command) {
            return Shell.run("sh", new String[]{
                    command
            }, null, false);
        }

        /**
         * Runs commands and return output
         *
         * @deprecated Consider using Shell.Pool.SH.run() instead
         *
         * @param commands The commands to run
         * @return Output of the commands, or null in case of an error
         */
        @Nullable
        @Deprecated
        @WorkerThread
        public static List<String> run(@NonNull List<String> commands) {
            return Shell.run("sh", commands.toArray(new String[0]), null, false);
        }

        /**
         * Runs commands and return output
         *
         * @deprecated Consider using Shell.Pool.SH.run() instead
         *
         * @param commands The commands to run
         * @return Output of the commands, or null in case of an error
         */
        @Nullable
        @Deprecated
        @WorkerThread
        public static List<String> run(@NonNull String[] commands) {
            return Shell.run("sh", commands, null, false);
        }
    }

    /**
     * This class provides utility functions to easily execute commands using SU
     * (root shell), as well as detecting whether or not root is available, and
     * if so which version.
     */
    public static class SU {
        @Nullable
        private static Boolean isSELinuxEnforcing = null;
        @NonNull
        private static String[] suVersion = new String[]{
                null, null
        };

        /**
         * Runs command as root (if available) and return output
         *
         * @deprecated Consider using Shell.Pool.SU.run() instead
         *
         * @param command The command to run
         * @return Output of the command, or null if root isn't available or in
         * case of an error
         */
        @Nullable
        @Deprecated
        @WorkerThread
        public static List<String> run(@NonNull String command) {
            return Shell.run("su", new String[]{
                    command
            }, null, false);
        }

        /**
         * Runs commands as root (if available) and return output
         *
         * @deprecated Consider using Shell.Pool.SU.run() instead
         *
         * @param commands The commands to run
         * @return Output of the commands, or null if root isn't available or in
         * case of an error
         */
        @Nullable
        @Deprecated
        @WorkerThread
        public static List<String> run(@NonNull List<String> commands) {
            return Shell.run("su", commands.toArray(new String[0]), null, false);
        }

        /**
         * Runs commands as root (if available) and return output
         *
         * @deprecated Consider using Shell.Pool.SU.run() instead
         *
         * @param commands The commands to run
         * @return Output of the commands, or null if root isn't available or in
         * case of an error
         */
        @Nullable
        @Deprecated
        @WorkerThread
        public static List<String> run(@NonNull String[] commands) {
            return Shell.run("su", commands, null, false);
        }

        /**
         * Detects whether or not superuser access is available, by checking the
         * output of the "id" command if available, checking if a shell runs at
         * all otherwise
         *
         * @return True if superuser access available
         */
        @WorkerThread
        public static boolean available() {
            // this is only one of many ways this can be done

            List<String> ret = run(Shell.availableTestCommands);
            return Shell.parseAvailableResult(ret, true);
        }

        /**
         * <p>
         * Detects the version of the su binary installed (if any), if supported
         * by the binary. Most binaries support two different version numbers,
         * the public version that is displayed to users, and an internal
         * version number that is used for version number comparisons. Returns
         * null if su not available or retrieving the version isn't supported.
         * </p>
         * <p>
         * Note that su binary version and GUI (APK) version can be completely
         * different.
         * </p>
         * <p>
         * This function caches its result to improve performance on multiple
         * calls
         * </p>
         *
         * @param internal Request human-readable version or application internal version
         * @return String containing the su version or null
         */
        @Nullable
        @WorkerThread // if not cached
        public static synchronized String version(boolean internal) {
            int idx = internal ? 0 : 1;
            if (suVersion[idx] == null) {
                String version = null;

                List<String> ret;
                if (!redirectDeprecated) {
                    ret = Shell.run(
                            internal ? "su -V" : "su -v",
                            new String[] { "exit" },
                            null,
                            false
                    );
                } else {
                    ret = new ArrayList<String>();
                    try {
                        ret = new ArrayList<String>();
                        Shell.Pool.SH.run(
                                new String[] {
                                    internal ? "su -V" : "su -v",
                                    "exit"
                                },
                                ret,
                                null,
                                false
                        );
                    } catch (ShellDiedException e) {
                        // no action
                    }
                }

                if (ret != null) {
                    for (String line : ret) {
                        if (!internal) {
                            if (!line.trim().equals("")) {
                                version = line;
                                break;
                            }
                        } else {
                            try {
                                if (Integer.parseInt(line) > 0) {
                                    version = line;
                                    break;
                                }
                            } catch (NumberFormatException e) {
                                // should be parsable, try next line otherwise
                            }
                        }
                    }
                }

                suVersion[idx] = version;
            }
            return suVersion[idx];
        }

        /**
         * Attempts to deduce if the shell command refers to a su shell
         *
         * @param shell Shell command to run
         * @return Shell command appears to be su
         */
        @AnyThread
        public static boolean isSU(String shell) {
            // Strip parameters
            int pos = shell.indexOf(' ');
            if (pos >= 0) {
                shell = shell.substring(0, pos);
            }

            // Strip path
            pos = shell.lastIndexOf('/');
            if (pos >= 0) {
                shell = shell.substring(pos + 1);
            }

            return shell.toLowerCase(Locale.ENGLISH).equals("su");
        }

        /**
         * Constructs a shell command to start a su shell using the supplied uid
         * and SELinux context. This is can be an expensive operation, consider
         * caching the result.
         *
         * @param uid Uid to use (0 == root)
         * @param context (SELinux) context name to use or null
         * @return Shell command
         */
        @NonNull
        @WorkerThread
        public static String shell(int uid, @Nullable String context) {
            // su[ --context <context>][ <uid>]
            String shell = "su";

            if ((context != null) && isSELinuxEnforcing()) {
                String display = version(false);
                String internal = version(true);

                // We only know the format for SuperSU v1.90+ right now
                //TODO add detection for other su's that support this
                if ((display != null) &&
                        (internal != null) &&
                        (display.endsWith("SUPERSU")) &&
                        (Integer.valueOf(internal) >= 190)) {
                    shell = String.format(Locale.ENGLISH, "%s --context %s", shell, context);
                }
            }

            // Most su binaries support the "su <uid>" format, but in case
            // they don't, lets skip it for the default 0 (root) case
            if (uid > 0) {
                shell = String.format(Locale.ENGLISH, "%s %d", shell, uid);
            }

            return shell;
        }

        /**
         * Constructs a shell command to start a su shell connected to mount
         * master daemon, to perform public mounts on Android 4.3+ (or 4.2+ in
         * SELinux enforcing mode)
         *
         * @return Shell command
         */
        @NonNull
        @AnyThread
        public static String shellMountMaster() {
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                return "su --mount-master";
            }
            return "su";
        }

        /**
         * Detect if SELinux is set to enforcing, caches result
         *
         * @return true if SELinux set to enforcing, or false in the case of permissive or not present
         */
        @SuppressLint("PrivateApi")
        @WorkerThread
        public static synchronized boolean isSELinuxEnforcing() {
            if (isSELinuxEnforcing == null) {
                Boolean enforcing = null;

                // First known firmware with SELinux built-in was a 4.2 (17)
                // leak
                if (android.os.Build.VERSION.SDK_INT >= 17) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        // Due to non-SDK API greylisting, we cannot determine SELinux status
                        // through the methods below, so we assume SELinux is enforcing and
                        // potentially patch policies for nothing
                        enforcing = true;
                    }

                    // Detect enforcing through sysfs, not always present
                    if (enforcing == null) {
                        File f = new File("/sys/fs/selinux/enforce");
                        if (f.exists()) {
                            try {
                                InputStream is = new FileInputStream("/sys/fs/selinux/enforce");
                                try {
                                    enforcing = (is.read() == '1');
                                } finally {
                                    is.close();
                                }
                            } catch (Exception e) {
                                // we might not be allowed to read, thanks SELinux
                            }
                        }
                    }

                    // 4.4+ has a new API to detect SELinux mode, so use it
                    // SELinux is typically in enforced mode, but emulators may have SELinux disabled
                    if (enforcing == null) {
                        try {
                            Class<?> seLinux = Class.forName("android.os.SELinux");
                            Method isSELinuxEnforced = seLinux.getMethod("isSELinuxEnforced");
                            enforcing = (Boolean) isSELinuxEnforced.invoke(seLinux.newInstance());
                        } catch (Exception e) {
                            // 4.4+ release builds are enforcing by default, take the gamble
                            enforcing = (android.os.Build.VERSION.SDK_INT >= 19);
                        }
                    }
                }

                if (enforcing == null) {
                    enforcing = false;
                }

                isSELinuxEnforcing = enforcing;
            }
            return isSELinuxEnforcing;
        }

        /**
         * <p>
         * Clears results cached by isSELinuxEnforcing() and version(boolean
         * internal) calls.
         * </p>
         * <p>
         * Most apps should never need to call this, as neither enforcing status
         * nor su version is likely to change on a running device - though it is
         * not impossible.
         * </p>
         */
        @AnyThread
        public static synchronized void clearCachedResults() {
            isSELinuxEnforcing = null;
            suVersion[0] = null;
            suVersion[1] = null;
        }
    }

    /**
     * DO NOT USE DIRECTLY. Base interface for result callbacks.
     */
    public interface OnResult {
        // for any callback
        int WATCHDOG_EXIT = -1;
        int SHELL_DIED = -2;

        // for Interactive.open() callbacks only
        int SHELL_EXEC_FAILED = -3;
        int SHELL_WRONG_UID = -4;
        int SHELL_RUNNING = 0;
    }

    /**
     * Callback for {@link Shell.Builder#open(Shell.OnShellOpenResultListener)}
     */
    public interface OnShellOpenResultListener extends OnResult {
        /**
         * Callback for shell open result
         *
         * @param success whether the shell is opened
         * @param reason reason why the shell isn't opened
         */
        void onOpenResult(boolean success, int reason);
    }

    /**
     * Command result callback, notifies the recipient of the completion of a
     * command block, including the (last) exit code, and the full output
     *
     * @deprecated You probably want to use {@link OnCommandResultListener2} instead
     */
    @Deprecated
    public interface OnCommandResultListener extends OnResult {
        /**
         * <p>
         * Command result callback for STDOUT, optionally interleaved with STDERR
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * If wantSTDERR is set, output of STDOUT and STDERR is interleaved into
         * the output buffer. There are no guarantees of absolutely order
         * correctness (just like in a real terminal)
         * </p>
         * <p>
         * To get separate STDOUT and STDERR output, use {@link OnCommandResultListener2}
         * </p>
         * <p>
         * See {@link Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode Exit code of the last command in the block
         * @param output All output generated by the command block
         */
        void onCommandResult(int commandCode, int exitCode, @NonNull List<String> output);
    }

    /**
     * Command result callback, notifies the recipient of the completion of a
     * command block, including the (last) exit code, and the full output
     */
    public interface OnCommandResultListener2 extends OnResult {
        /**
         * <p>
         * Command result callback with separated STDOUT and STDERR
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * See {@link Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode Exit code of the last command in the block
         * @param STDOUT All STDOUT output generated by the command block
         * @param STDERR All STDERR output generated by the command block
         */
        void onCommandResult(int commandCode, int exitCode, @NonNull List<String> STDOUT, @NonNull List<String> STDERR);
    }

    /**
     * DO NOT USE DIRECTLY. Command result callback that doesn't cause output to be buffered
     */
    private interface OnCommandResultListenerUnbuffered extends OnResult {
        /**
         * <p>
         * Command result callback
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * See {@link Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode Exit code of the last command in the block
         */
        void onCommandResult(int commandCode, int exitCode);
    }

    /**
     * DO NOT USE DIRECTLY. Line callback for STDOUT
     */
    private interface OnCommandLineSTDOUT {
        /**
         * <p>
         * Line callback for STDOUT
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * See {@link Interactive} for threading details
         * </p>
         *
         * @param line One line of STDOUT output
         */
        void onSTDOUT(@NonNull String line);
    } 

    /**
     * DO NOT USE DIRECTLY. Line callback for STDERR
     */
    private interface OnCommandLineSTDERR {
        /**
         * <p>
         * Line callback for STDERR
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * See {@link Interactive} for threading details
         * </p>
         *
         * @param line One line of STDERR output
         */
        void onSTDERR(@NonNull String line);
    } 

    /**
     * Command per line callback for parsing the output line by line without
     * buffering. It also notifies the recipient of the completion of a command
     * block, including the (last) exit code.
     */
    public interface OnCommandLineListener extends OnCommandResultListenerUnbuffered, OnCommandLineSTDOUT, OnCommandLineSTDERR {
    }

    /**
     * DO NOT USE DIRECTLY. InputStream callback
     */
    public interface OnCommandInputStream extends OnCommandLineSTDERR {
        /**
         * <p>
         * InputStream callback
         * </p>
         * <p>
         * The read() methods will return -1 when all input is consumed, and throw an
         * IOException if the shell died before all data being read.
         * </p>
         * <p>
         * If a Handler is <i>not</i> setup, this callback may be executed on one of the
         * gobbler threads. In that case, it is important the callback returns as quickly
         * as possible, as delays in this callback may pause the native process or even
         * result in a deadlock. It may also be executed on the main thread, in which
         * case you should offload handling to a different thread
         * </p>
         * <p>
         * If a Handler <i>is</i> setup and it executes callbacks on the main thread,
         * you <i>should</i> offload handling to a different thread, as reading from
         * the InputStream would block your UI
         * </p>
         * <p>
         * You <i>must</i> drain the InputStream (read until it returns -1 or throws
         * an IOException), or call close(), otherwise execution of root commands will 
         * not continue. This cannot be solved automatically without keeping it safe to 
         * offload the InputStream to another thread.
         * </p>
         *
         * @param inputStream InputStream to read from
         */
        void onInputStream(@NonNull InputStream inputStream);
    }

    /**
     * Command InputStream callback for direct access to STDOUT. It also notifies the
     * recipient of the completion of a command block, including the (last) exit code.
     */
    public interface OnCommandInputStreamListener extends OnCommandResultListenerUnbuffered, OnCommandInputStream {
    }

    /**
     * Internal class to store command block properties
     */
    private static class Command {
        private static int commandCounter = 0;

        private final String[] commands;
        private final int code;
        @Nullable
        private final OnCommandResultListener onCommandResultListener;
        @Nullable
        private final OnCommandResultListener2 onCommandResultListener2;
        @Nullable
        private final OnCommandLineListener onCommandLineListener;
        @Nullable
        private final OnCommandInputStreamListener onCommandInputStreamListener;
        @NonNull
        private final String marker;

        @Nullable
        private volatile MarkerInputStream markerInputStream = null;

        @SuppressWarnings("unchecked") // if the user passes in List<> of anything other than String, that's on them
        public Command(@NonNull Object commands, int code, @Nullable OnResult listener) {
            if (commands instanceof String) {
                this.commands = new String[] { (String)commands };
            } else if (commands instanceof List<?>) {
                this.commands = ((List<String>)commands).toArray(new String[0]);
            } else if (commands instanceof String[]) {
                this.commands = (String[])commands;
            } else {
                throw new IllegalArgumentException("commands parameter must be of type String, List<String> or String[]");
            }
            this.code = code;
            this.marker = UUID.randomUUID().toString() + String.format(Locale.ENGLISH, "-%08x", ++commandCounter);

            OnCommandResultListener commandResultListener = null;
            OnCommandResultListener2 commandResultListener2 = null;
            OnCommandLineListener commandLineListener = null;
            OnCommandInputStreamListener commandInputStreamListener = null;
            if (listener != null) {
                if (listener instanceof OnCommandInputStreamListener) {
                    commandInputStreamListener = (OnCommandInputStreamListener)listener;
                } else if (listener instanceof OnCommandLineListener) {
                    commandLineListener = (OnCommandLineListener)listener;
                } else if (listener instanceof OnCommandResultListener2) {
                    commandResultListener2 = (OnCommandResultListener2)listener;
                } else if (listener instanceof OnCommandResultListener) {
                    commandResultListener = (OnCommandResultListener)listener;
                } else {
                    throw new IllegalArgumentException("OnResult is not a supported callback interface");
                }
            }
            this.onCommandResultListener = commandResultListener;
            this.onCommandResultListener2 = commandResultListener2;
            this.onCommandLineListener = commandLineListener;
            this.onCommandInputStreamListener = commandInputStreamListener;
        }
    }

    /**
     * Builder class for {@link Interactive}
     */
    @AnyThread
    public static class Builder {
        @Nullable
        private Handler handler = null;
        private boolean autoHandler = true;
        private String shell = "sh";
        private boolean wantSTDERR = false;
        private boolean shellDiesOnSTDOUTERRClose = true;
        private boolean detectOpen = true;
        @NonNull
        private List<Command> commands = new LinkedList<Command>();
        @NonNull
        private Map<String, String> environment = new HashMap<String, String>();
        @Nullable
        private OnLineListener onSTDOUTLineListener = null;
        @Nullable
        private OnLineListener onSTDERRLineListener = null;
        private int watchdogTimeout = 0;

        /**
         * <p>
         * Set a custom handler that will be used to post all callbacks to
         * </p>
         * <p>
         * See {@link Interactive} for further details on threading and
         * handlers
         * </p>
         *
         * @param handler Handler to use
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setHandler(@Nullable Handler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * <p>
         * Automatically create a handler if possible ? Default to true
         * </p>
         * <p>
         * See {@link Interactive} for further details on threading and
         * handlers
         * </p>
         *
         * @param autoHandler Auto-create handler ?
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setAutoHandler(boolean autoHandler) {
            this.autoHandler = autoHandler;
            return this;
        }

        /**
         * Set shell binary to use. Usually "sh" or "su", do not use a full path
         * unless you have a good reason to
         *
         * @param shell Shell to use
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setShell(@NonNull String shell) {
            this.shell = shell;
            return this;
        }

        /**
         * Convenience function to set "sh" as used shell
         *
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder useSH() {
            return setShell("sh");
        }

        /**
         * Convenience function to set "su" as used shell
         *
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder useSU() {
            return setShell("su");
        }

        /**
         * <p>
         * Detect whether the shell was opened correctly ?
         * </p>
         * <p>
         * When active, this runs test commands in the shell
         * before it runs your own commands to determine if
         * the shell is functioning correctly. This is also
         * required for the {@link Interactive#isOpening()}
         * method to return a proper result
         * </p>
         * <p>
         * You probably want to keep this turned on, the
         * option to turn it off exists only to support
         * code using older versions of this library that
         * may depend on these commands <i>not</i> being
         * run
         * </p>
         *
         * @deprecated New users should leave the default
         *
         * @param detectOpen Detect shell running properly (default true)
         * @return This Builder object for method chaining
         */
        @NonNull
        @Deprecated
        public Builder setDetectOpen(boolean detectOpen) {
            this.detectOpen = detectOpen;
            return this;
        }

        /**
         * <p>
         * Treat STDOUT/STDERR close as shell death ?
         * </p>
         * <p>
         * You probably want to keep this turned on. It is not
         * completely unthinkable you may need to turn this off,
         * but it is unlikely. Turning it off will break dead
         * shell detection on commands providing an InputStream
         * </p>
         *
         * @deprecated New users should leave the default unless absolutely necessary
         *
         * @param shellDies Treat STDOUT/STDERR close as shell death (default true)
         * @return This Builder object for method chaining
         */
        @NonNull
        @Deprecated
        public Builder setShellDiesOnSTDOUTERRClose(boolean shellDies) {
            this.shellDiesOnSTDOUTERRClose = shellDies;
            return this;
        }

        /**
         * <p>
         * Set if STDERR output should be interleaved with STDOUT output (only) when {@link OnCommandResultListener} is used
         * </p>
         * <p>
         * If you want separate STDOUT and STDERR output, use {@link OnCommandResultListener2} instead
         * </p>
         *
         * @deprecated You probably want to use {@link OnCommandResultListener2}, which ignores this setting
         *
         * @param wantSTDERR Want error output ?
         * @return This Builder object for method chaining
         */
        @NonNull
        @Deprecated
        public Builder setWantSTDERR(boolean wantSTDERR) {
            this.wantSTDERR = wantSTDERR;
            return this;
        }

        /**
         * Add or update an environment variable
         *
         * @param key Key of the environment variable
         * @param value Value of the environment variable
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder addEnvironment(@NonNull String key, @NonNull String value) {
            environment.put(key, value);
            return this;
        }

        /**
         * Add or update environment variables
         *
         * @param addEnvironment Map of environment variables
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder addEnvironment(@NonNull Map<String, String> addEnvironment) {
            environment.putAll(addEnvironment);
            return this;
        }

        /**
         * Add commands to execute, without a callback
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder addCommand(@NonNull Object commands) {
            return addCommand(commands, 0, null);
        }

        /**
         * <p>
         * Add commands to execute, with a callback. Several callback interfaces are supported
         * </p>
         * <p>
         * {@link OnCommandResultListener2}: provides only a callback with the result of the entire
         * command and the (last) exit code. The results are buffered until command completion, so
         * commands that generate massive amounts of output should use {@link OnCommandLineListener}
         * instead.
         * </p>
         * <p>
         * {@link OnCommandLineListener}: provides a per-line callback without internal buffering.
         * Also provides a command completion callback with the (last) exit code.
         * </p>
         * <p>
         * {@link OnCommandInputStreamListener}: provides a callback that is called with an
         * InputStream you can read STDOUT from directly. Also provides a command completion
         * callback with the (last) exit code. Note that this callback ignores the watchdog.
         * </p>
         * <p>
         * The thread on which the callback executes is dependent on various
         * factors, see {@link Interactive} for further details
         * </p>
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder addCommand(@NonNull Object commands, int code, @Nullable OnResult onResultListener) {
            this.commands.add(new Command(commands, code, onResultListener));
            return this;
        }

        /**
         * <p>
         * Set a callback called for every line output to STDOUT by the shell
         * </p>
         * <p>
         * The thread on which the callback executes is dependent on various
         * factors, see {@link Interactive} for further details
         * </p>
         *
         * @param onLineListener Callback to be called for each line
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setOnSTDOUTLineListener(@Nullable OnLineListener onLineListener) {
            this.onSTDOUTLineListener = onLineListener;
            return this;
        }

        /**
         * <p>
         * Set a callback called for every line output to STDERR by the shell
         * </p>
         * <p>
         * The thread on which the callback executes is dependent on various
         * factors, see {@link Interactive} for further details
         * </p>
         *
         * @param onLineListener Callback to be called for each line
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setOnSTDERRLineListener(@Nullable OnLineListener onLineListener) {
            this.onSTDERRLineListener = onLineListener;
            return this;
        }

        /**
         * <p>
         * Enable command timeout callback
         * </p>
         * <p>
         * This will invoke the onCommandResult() callback with exitCode
         * WATCHDOG_EXIT if a command takes longer than watchdogTimeout seconds
         * to complete.
         * </p>
         * <p>
         * If a watchdog timeout occurs, it generally means that the Interactive
         * session is out of sync with the shell process. The caller should
         * close the current session and open a new one.
         * </p>
         *
         * @param watchdogTimeout Timeout, in seconds; 0 to disable
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setWatchdogTimeout(int watchdogTimeout) {
            this.watchdogTimeout = watchdogTimeout;
            return this;
        }

        /**
         * <p>
         * Enable/disable reduced logcat output
         * </p>
         * <p>
         * Note that this is a global setting
         * </p>
         *
         * @param useMinimal true for reduced output, false for full output
         * @return This Builder object for method chaining
         */
        @NonNull
        public Builder setMinimalLogging(boolean useMinimal) {
            Debug.setLogTypeEnabled(Debug.LOG_COMMAND | Debug.LOG_OUTPUT, !useMinimal);
            return this;
        }

        /**
         * Construct a {@link Interactive} instance, and start the shell
         *
         * @return Interactive shell
         */
        @NonNull
        public Interactive open() {
            return new Interactive(this, null);
        }

        /**
         * Construct a {@link Interactive} instance, try to start the
         * shell, and call onShellOpenResultListener to report success or failure
         *
         * @param onShellOpenResultListener Callback to return shell open status
         * @return Interactive shell
         */
        @NonNull
        public Interactive open(@Nullable OnShellOpenResultListener onShellOpenResultListener) {
            return new Interactive(this, onShellOpenResultListener);
        }

        /**
         * <p>
         * Construct a {@link Threaded} instance, and start the shell
         * </p>
         * <p>
         * {@link Threaded} ignores the {@link #setHandler(Handler)},
         * {@link #setAutoHandler(boolean)}, {@link #setDetectOpen(boolean)}
         * and {@link #setShellDiesOnSTDOUTERRClose(boolean)} settings on this
         * Builder and uses its own values
         * </p>
         * <p>
         * On API &gt;= 19, the return value is {@link ThreadedAutoCloseable}
         * rather than {@link Threaded}
         * </p>
         *
         * @return Threaded interactive shell
         */
        @NonNull
        public Threaded openThreaded() {
            return openThreadedEx(null, false);
        }

        /**
         * <p>
         * Construct a {@link Threaded} instance, try to start the
         * shell, and call onShellOpenResultListener to report success or failure
         * </p>
         * <p>
         * {@link Threaded} ignores the {@link #setHandler(Handler)},
         * {@link #setAutoHandler(boolean)}, {@link #setDetectOpen(boolean)}
         * and {@link #setShellDiesOnSTDOUTERRClose(boolean)} settings on this
         * Builder and uses its own values
         * </p>
         * <p>
         * On API &gt;= 19, the return value is {@link ThreadedAutoCloseable}
         * rather than {@link Threaded}
         * </p>
         *
         * @param onShellOpenResultListener Callback to return shell open status
         * @return Threaded interactive shell
         */
        @NonNull
        public Threaded openThreaded(@Nullable OnShellOpenResultListener onShellOpenResultListener) {
            return openThreadedEx(onShellOpenResultListener, false);
        }

        private Threaded openThreadedEx(OnShellOpenResultListener onShellOpenResultListener, boolean pooled) {
            if (Build.VERSION.SDK_INT >= 19) {
                return new ThreadedAutoCloseable(this, onShellOpenResultListener, pooled);
            } else {
                return new Threaded(this, onShellOpenResultListener, pooled);
            }
        }
    }

    /**
     * Callback interface for {@link SyncCommands#run(Object, Shell.OnSyncCommandLineListener)}
     */
    public interface OnSyncCommandLineListener extends OnCommandLineSTDOUT, OnCommandLineSTDERR {
    }

    /**
     * Callback interface for {@link SyncCommands#run(Object, Shell.OnSyncCommandInputStreamListener)}
     */
    public interface OnSyncCommandInputStreamListener extends OnCommandInputStream, OnCommandLineSTDERR {
    }

    /**
     * Base interface for objects that support deprecated synchronous commands
     */
    @Deprecated
    @WorkerThread
    public interface DeprecatedSyncCommands {
        /**
         * Run commands, returning the output, or null on error
         *
         * @deprecated This methods exists only as drop-in replacement for Shell.SU/SH.run() methods and should not be used by new users
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param wantSTDERR Return STDERR in the output ?
         * @return Output of the commands, or null in case of an error
         */
        @Nullable
        @Deprecated
        List<String> run(@NonNull Object commands, boolean wantSTDERR);

        /**
         * Run commands, with a set environment, returning the output, or null on error
         *
         * @deprecated This methods exists only as drop-in replacement for Shell.SU/SH.run() methods and should not be used by new users
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param environment List of all environment variables (in 'key=value' format) or null for defaults
         * @param wantSTDERR Return STDERR in the output ?
         * @return Output of the commands, or null in case of an error
         */
        @Nullable
        @Deprecated
        List<String> run(@NonNull Object commands, @Nullable String[] environment, boolean wantSTDERR);
    }

    /**
     * Base interface for objects that support synchronous commands
     */
    @WorkerThread
    public interface SyncCommands {
        /**
         * Run commands and return exit code
         * 
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @return Exit code
         * @throws ShellDiedException if shell is closed, was closed during command execution, or was never open (access denied)
         */
        int run(@NonNull Object commands) throws ShellDiedException;

        /**
         * <p>
         * Run commands and return STDOUT and STDERR output, and exit code
         * </p>
         * <p>
         * Note that all output is buffered, and very large outputs may cause you to run out of
         * memory.
         * </p>
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param STDOUT List&lt;String&gt; to receive STDOUT output, or null
         * @param STDERR List&lt;String&gt; to receive STDERR output, or null
         * @param clear Clear STDOUT/STDOUT before adding output ?
         * @return Exit code
         * @throws ShellDiedException if shell is closed, was closed during command execution, or was never open (access denied)
         */
        int run(@NonNull Object commands, @Nullable List<String> STDOUT, @Nullable List<String> STDERR, boolean clear) throws ShellDiedException;

        /**
         * <p>
         * Run commands using a callback that receives per-line STDOUT and STDERR output as they happen, and returns exit code
         * </p>
         * <p>
         * You should <i>not</i> call other synchronous methods from the callback
         * </p>
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param onSyncCommandLineListener Callback interface for per-line output
         * @return Exit code
         * @throws ShellDiedException if shell is closed, was closed during command execution, or was never open (access denied)
         */
        int run(@NonNull Object commands, @NonNull OnSyncCommandLineListener onSyncCommandLineListener) throws ShellDiedException;

        /**
         * <p>
         * Run commands using a callback that receives an InputStream for STDOUT and per-line STDERR output as it happens, and returns exit code
         * </p>
         * <p>
         * You should <i>not</i> call other synchronous methods from the callback
         * </p>
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param onSyncCommandInputStreamListener Callback interface for InputStream output
         * @return Exit code
         * @throws ShellDiedException if shell is closed, was closed during command execution, or was never open (access denied)
         */
        int run(@NonNull Object commands, @NonNull OnSyncCommandInputStreamListener onSyncCommandInputStreamListener) throws ShellDiedException;
    }

    /**
     * <p>
     * An interactive shell - initially created with {@link Builder} -
     * that executes blocks of commands you supply in the background, optionally
     * calling callbacks as each block completes.
     * </p>
     * <p>
     * STDERR output can be supplied as well, but (just like in a real terminal)
     * output order between STDOUT and STDERR cannot be guaranteed to be correct.
     * </p>
     * <p>
     * Note as well that the close() and waitForIdle() methods will
     * intentionally crash when run in debug mode from the main thread of the
     * application. Any blocking call should be run from a background thread.
     * </p>
     * <p>
     * When in debug mode, the code will also excessively log the commands
     * passed to and the output returned from the shell.
     * </p>
     * <p>
     * Background threads are used to gobble STDOUT and STDERR so a deadlock does
     * not occur if the shell produces massive output, but if you're using
     * {@link OnCommandResultListener} or {@link OnCommandResultListener2} for
     * callbacks, the output gets added to a List&lt;String&gt; until the command
     * completes. As such if you're doing something like <em>'ls -lR /'</em> you
     * will probably run out of memory. A work-around is to use {@link OnCommandLineListener}
     * which does not buffer the data nor waste memory, but you should make sure
     * those callbacks do not block unnecessarily.
     * </p>
     * <h3>Callbacks, threads and handlers</h3>
     * <p>
     * On which thread the callbacks execute is dependent on your
     * initialization. You can supply a custom Handler using
     * {@link Builder#setHandler(Handler)} if needed. If you do not supply
     * a custom Handler - unless you set
     * {@link Builder#setAutoHandler(boolean)} to false - a Handler will
     * be auto-created if the thread used for instantiation of the object has a
     * Looper.
     * </p>
     * <p>
     * If no Handler was supplied and it was also not auto-created, all
     * callbacks will be called from either the STDOUT or STDERR gobbler
     * threads. These are important threads that should be blocked as little as
     * possible, as blocking them may in rare cases pause the native process or
     * even create a deadlock.
     * </p>
     * <p>
     * The main thread must certainly have a Looper, thus if you call
     * {@link Builder#open()} from the main thread, a handler will (by
     * default) be auto-created, and all the callbacks will be called on the
     * main thread. While this is often convenient and easy to code with, you
     * should be aware that if your callbacks are 'expensive' to execute, this
     * may negatively impact UI performance.
     * </p>
     * <p>
     * Background threads usually do <em>not</em> have a Looper, so calling
     * {@link Builder#open()} from such a background thread will (by
     * default) result in all the callbacks being executed in one of the gobbler
     * threads. You will have to make sure the code you execute in these
     * callbacks is thread-safe.
     * </p>
     */
    public static class Interactive implements SyncCommands {
        @Nullable
        protected final Handler handler;
        private final boolean autoHandler;
        private final String shell;
        private boolean shellDiesOnSTDOUTERRClose;
        private final boolean wantSTDERR;
        @NonNull
        private final List<Command> commands;
        @NonNull
        private final Map<String, String> environment;
        @Nullable
        private final OnLineListener onSTDOUTLineListener;
        @Nullable
        private final OnLineListener onSTDERRLineListener;
        private int watchdogTimeout;

        @Nullable
        private Process process = null;
        @Nullable
        private DataOutputStream STDIN = null;
        @Nullable
        private StreamGobbler STDOUT = null;
        @Nullable
        private StreamGobbler STDERR = null;
        private final Object STDclosedSync = new Object();
        private boolean STDOUTclosed = false;
        private boolean STDERRclosed = false;
        @Nullable
        private ScheduledThreadPoolExecutor watchdog = null;

        private volatile boolean running = false;
        private volatile boolean lastOpening = false;
        private volatile boolean opening = false;
        private volatile boolean idle = true; // read/write only synchronized
        protected volatile boolean closed = true;
        protected volatile int callbacks = 0;
        private volatile int watchdogCount;
        private volatile boolean doCloseWhenIdle = false;
        protected volatile boolean inClosingJoin = false;

        private final Object idleSync = new Object();
        protected final Object callbackSync = new Object();
        private final Object openingSync = new Object();
        private final List<String> emptyStringList = new ArrayList<String>();

        private volatile int lastExitCode = 0;
        @Nullable
        private volatile String lastMarkerSTDOUT = null;
        @Nullable
        private volatile String lastMarkerSTDERR = null;
        @Nullable
        private volatile Command command = null;
        @Nullable
        private volatile List<String> bufferSTDOUT = null;
        @Nullable
        private volatile List<String> bufferSTDERR = null;

        /**
         * The only way to create an instance: Shell.Builder::open(...)
         *
         * @see Shell.Builder#open()
         * @see Shell.Builder#open(Shell.OnShellOpenResultListener)
         *
         * @param builder Builder class to take values from
         * @param onShellOpenResultListener Callback
         */
        @AnyThread
        protected Interactive(@NonNull final Builder builder,
                              @Nullable final OnShellOpenResultListener onShellOpenResultListener) {
            autoHandler = builder.autoHandler;
            shell = builder.shell;
            shellDiesOnSTDOUTERRClose = builder.shellDiesOnSTDOUTERRClose;
            wantSTDERR = builder.wantSTDERR;
            commands = builder.commands;
            environment = builder.environment;
            onSTDOUTLineListener = builder.onSTDOUTLineListener;
            onSTDERRLineListener = builder.onSTDERRLineListener;
            watchdogTimeout = builder.watchdogTimeout;

            // If a looper is available, we offload the callbacks from the gobbling threads to
            // whichever thread created us. Would normally do this in open(), but then we could
            // not declare handler as final
            if ((Looper.myLooper() != null) && (builder.handler == null) && autoHandler) {
                handler = new Handler();
            } else {
                handler = builder.handler;
            }

            if ((onShellOpenResultListener != null) || builder.detectOpen) {
                lastOpening = true;
                opening = true;

                // Allow up to 60 seconds for SuperSU/Superuser dialog, then enable
                // the user-specified timeout for all subsequent operations
                watchdogTimeout = 60;
                commands.add(0, new Command(Shell.availableTestCommands, 0, new OnCommandResultListener2() {
                    @Override
                    public void onCommandResult(int commandCode, int exitCode, @NonNull List<String> STDOUT, @NonNull List<String> STDERR) {
                        // we don't set opening to false here because idle must be set to true first
                        // to prevent falling through if 'isOpening() || isIdle()' is called

                        // this always runs in one of the gobbler threads, hard-coded
                        if ((exitCode == OnCommandResultListener2.SHELL_RUNNING) &&
                                !Shell.parseAvailableResult(STDOUT, Shell.SU.isSU(shell))) {
                            // shell is up, but it's brain-damaged
                            exitCode = OnCommandResultListener2.SHELL_WRONG_UID;

                            // we're otherwise technically not idle in this callback, deadlock
                            // we're inside runNextCommand so we needn't bother with idleSync
                            idle = true;
                            closeImmediately(); // triggers SHELL_DIED on remaining commands
                        }

                        // reset watchdog to user value
                        watchdogTimeout = builder.watchdogTimeout;

                        // callback
                        if (onShellOpenResultListener != null) {
                            if (handler != null) {
                                final int fExitCode = exitCode;
                                startCallback();
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            onShellOpenResultListener.onOpenResult(fExitCode == OnShellOpenResultListener.SHELL_RUNNING, fExitCode);
                                        } finally {
                                            endCallback();
                                        }
                                    }
                                });
                            } else {
                                onShellOpenResultListener.onOpenResult(exitCode == OnShellOpenResultListener.SHELL_RUNNING, exitCode);
                            }
                        }
                    }
                }));
            }

            if (!open() && (onShellOpenResultListener != null)) {
                if (handler != null) {
                    startCallback();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                onShellOpenResultListener.onOpenResult(false, OnShellOpenResultListener.SHELL_EXEC_FAILED);
                            } finally {
                                endCallback();
                            }
                        }
                    });
                } else {
                    onShellOpenResultListener.onOpenResult(false, OnShellOpenResultListener.SHELL_EXEC_FAILED);
                }
            }
        }

        @Override
        protected void finalize() throws Throwable {
            if (!closed && Debug.getSanityChecksEnabledEffective()) {
                // waste of resources
                Debug.log(ShellNotClosedException.EXCEPTION_NOT_CLOSED);
                throw new ShellNotClosedException();
            }
            super.finalize();
        }

        /**
         * Add commands to execute, without a callback
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         */
        @AnyThread
        public synchronized void addCommand(@NonNull Object commands) {
            addCommand(commands, 0, null);
        }

        /**
         * Add commands to execute with a callback. See {@link Shell.Builder#addCommand(Object, int, Shell.OnResult)}
         * for details
         *
         * @see Shell.Builder#addCommand(Object, int, Shell.OnResult)
         *
         * @param commands Commands to execute, accepts String, List&lt;String&gt;, and String[]
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         */
        @AnyThread
        public synchronized void addCommand(@NonNull Object commands, int code, @Nullable OnResult onResultListener) {
            this.commands.add(new Command(commands, code, onResultListener));
            runNextCommand();
        }

        /**
         * Run the next command if any and if ready, signals idle state if no
         * commands left
         */
        private void runNextCommand() {
            runNextCommand(true);
        }

        /**
         * Called from a ScheduledThreadPoolExecutor timer thread every second
         * when there is an outstanding command
         */
        private synchronized void handleWatchdog() {
            final int exitCode;

            if (watchdog == null)
                return;
            if (watchdogTimeout == 0)
                return;

            if (!isRunning()) {
                exitCode = OnResult.SHELL_DIED;
                Debug.log(String.format(Locale.ENGLISH, "[%s%%] SHELL_DIED", shell.toUpperCase(Locale.ENGLISH)));
            } else if (watchdogCount++ < watchdogTimeout) {
                return;
            } else {
                exitCode = OnResult.WATCHDOG_EXIT;
                Debug.log(String.format(Locale.ENGLISH, "[%s%%] WATCHDOG_EXIT", shell.toUpperCase(Locale.ENGLISH)));
            }

            if (command != null) {
                //noinspection ConstantConditions // all write to 'command' are synchronized
                postCallback(command, exitCode, bufferSTDOUT, bufferSTDERR, null);
            }

            // prevent multiple callbacks for the same command
            command = null;
            bufferSTDOUT = null;
            bufferSTDERR = null;
            idle = true;
            opening = false;

            watchdog.shutdown();
            watchdog = null;
            kill();
        }

        /**
         * Start the periodic timer when a command is submitted
         */
        private void startWatchdog() {
            if (watchdogTimeout == 0) {
                return;
            }
            watchdogCount = 0;
            watchdog = new ScheduledThreadPoolExecutor(1);
            watchdog.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    handleWatchdog();
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        /**
         * Disable the watchdog timer upon command completion
         */
        private void stopWatchdog() {
            if (watchdog != null) {
                watchdog.shutdownNow();
                watchdog = null;
            }
        }

        /**
         * Run the next command if any and if ready
         *
         * @param notifyIdle signals idle state if no commands left ?
         */
        private void runNextCommand(boolean notifyIdle) {
            // must always be called from a synchronized method

            boolean running = isRunning();
            if (!running || closed) {
                idle = true;
                opening = false;
            }

            if (running && !closed && idle && (commands.size() > 0)) {
                Command command = commands.get(0);
                commands.remove(0);

                bufferSTDOUT = null;
                bufferSTDERR = null;
                lastExitCode = 0;
                lastMarkerSTDOUT = null;
                lastMarkerSTDERR = null;

                if (command.commands.length > 0) {
                    // STDIN and STDOUT would never be null here, but checks added to satisfy lint
                    if ((STDIN != null) && (STDOUT != null)) {
                        try {
                            if (command.onCommandResultListener != null) {
                                bufferSTDOUT = Collections.synchronizedList(new ArrayList<String>());
                            } else if (command.onCommandResultListener2 != null) {
                                bufferSTDOUT = Collections.synchronizedList(new ArrayList<String>());
                                bufferSTDERR = Collections.synchronizedList(new ArrayList<String>());
                            }

                            idle = false;
                            this.command = command;
                            if (command.onCommandInputStreamListener != null) {
                                if (!STDOUT.isSuspended()) {
                                    if (Thread.currentThread().getId() == STDOUT.getId()) {
                                        // if we're on the Gobbler thread we can suspend immediately,
                                        // as we're not currently in a readLine() call
                                        STDOUT.suspendGobbling();
                                    } else {
                                        // if not, we trigger the readLine() call in the Gobbler to
                                        // complete, and have the suspend triggered in the next
                                        // onLine call
                                        STDIN.write(("echo inputstream\n").getBytes("UTF-8"));
                                        STDIN.flush();
                                        STDOUT.waitForSuspend();
                                    }
                                }
                            } else {
                                STDOUT.resumeGobbling();
                                startWatchdog();
                            }
                            for (String write : command.commands) {
                                Debug.logCommand(String.format(Locale.ENGLISH, "[%s+] %s",
                                        shell.toUpperCase(Locale.ENGLISH), write));
                                STDIN.write((write + "\n").getBytes("UTF-8"));
                            }
                            STDIN.write(("echo " + command.marker + " $?\n").getBytes("UTF-8"));
                            STDIN.write(("echo " + command.marker + " >&2\n").getBytes("UTF-8"));
                            STDIN.flush();
                            if (command.onCommandInputStreamListener != null) {
                                command.markerInputStream = new MarkerInputStream(STDOUT, command.marker);
                                postCallback(command, 0, null, null, command.markerInputStream);
                            }
                        } catch (IOException e) {
                            // STDIN might have closed
                        }
                    }
                } else {
                    runNextCommand(false);
                }
            } else if (!running || closed) {
                // our shell died for unknown reasons or was closed - abort all submissions
                Debug.log(String.format(Locale.ENGLISH, "[%s%%] SHELL_DIED", shell.toUpperCase(Locale.ENGLISH)));
                while (commands.size() > 0) {
                    postCallback(commands.remove(0), OnResult.SHELL_DIED, null, null, null);
                }
                onClosed();
            }

            if (idle) {
                if (running && doCloseWhenIdle) {
                    doCloseWhenIdle = false;
                    closeImmediately(true);
                }
                if (notifyIdle) {
                    synchronized (idleSync) {
                        idleSync.notifyAll();
                    }
                }
            }

            if (lastOpening && !opening) {
                lastOpening = opening;
                synchronized (openingSync) {
                    openingSync.notifyAll();
                }
            }
        }

        /**
         * Processes a STDOUT/STDERR line containing an end/exitCode marker
         */
        @SuppressWarnings("ConstantConditions") // all writes to 'command' are synchronized
        private synchronized void processMarker() {
            if ((command != null) &&
                    command.marker.equals(lastMarkerSTDOUT) &&
                    command.marker.equals(lastMarkerSTDERR)) {
                postCallback(command, lastExitCode, bufferSTDOUT, bufferSTDERR, null);
                stopWatchdog();
                command = null;
                bufferSTDOUT = null;
                bufferSTDERR = null;
                idle = true;
                opening = false;
                runNextCommand();
            }
        }

        /**
         * Process a normal STDOUT/STDERR line, post to callback
         *
         * @param line Line to process
         * @param listener Callback to call or null, supports OnLineListener, OnCommandLineSTDOUT, OnCommandLineSTDERR
         */
        private synchronized void processLine(@NonNull final String line, @Nullable final Object listener, final boolean isSTDERR) {
            if (listener != null) {
                if (handler != null) {
                    startCallback();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (listener instanceof OnLineListener) {
                                    ((OnLineListener)listener).onLine(line);
                                } else if ((listener instanceof OnCommandLineSTDOUT) && !isSTDERR) {
                                    ((OnCommandLineSTDOUT)listener).onSTDOUT(line);
                                } else if ((listener instanceof OnCommandLineSTDERR) && isSTDERR) {
                                    ((OnCommandLineSTDERR)listener).onSTDERR(line);
                                }
                            } finally {
                                endCallback();
                            }
                        }
                    });
                } else {
                    if (listener instanceof OnLineListener) {
                        ((OnLineListener)listener).onLine(line);
                    } else if ((listener instanceof OnCommandLineSTDOUT) && !isSTDERR) {
                        ((OnCommandLineSTDOUT)listener).onSTDOUT(line);
                    } else if ((listener instanceof OnCommandLineSTDERR) && isSTDERR) {
                        ((OnCommandLineSTDERR)listener).onSTDERR(line);
                    }
                }
            }
        }

        /**
         * Add line to internal buffer
         *
         * @param line Line to add
         */
        @SuppressWarnings("ConstantConditions") // all writes to bufferSTDxxx are synchronized
        private synchronized void addBuffer(@NonNull String line, boolean isSTDERR) {
            if (isSTDERR) {
                if (bufferSTDERR != null) {
                    bufferSTDERR.add(line);
                } else if (wantSTDERR && (bufferSTDOUT != null)) {
                    bufferSTDOUT.add(line);
                }
            } else if (bufferSTDOUT != null) {
                bufferSTDOUT.add(line);
            }
        }

        /**
         * Increase callback counter
         */
        void startCallback() {
            synchronized (callbackSync) {
                callbacks++;
            }
        }

        /**
         * Schedule a callback to run on the appropriate thread
         *
         * @return if callback has already completed
         */
        private boolean postCallback(@NonNull final Command fCommand, final int fExitCode,
                                     @Nullable final List<String> fSTDOUT, @Nullable final List<String> fSTDERR,
                                     @Nullable final InputStream inputStream) {
            if (
                    (fCommand.onCommandResultListener == null) &&
                    (fCommand.onCommandResultListener2 == null) &&
                    (fCommand.onCommandLineListener == null) &&
                    (fCommand.onCommandInputStreamListener == null)
            ) {
                return true;
            }

            // we run the shell open test commands result immediately even if we have a handler, so
            // it may close the shell before other commands start and pass them SHELL_DIED exit code
            if ((handler == null) || (fCommand.commands == availableTestCommands)) {
                if (inputStream == null) {
                    if (fCommand.onCommandResultListener != null)
                        fCommand.onCommandResultListener.onCommandResult(fCommand.code, fExitCode, fSTDOUT != null ? fSTDOUT : emptyStringList);
                    if (fCommand.onCommandResultListener2 != null)
                        fCommand.onCommandResultListener2.onCommandResult(fCommand.code, fExitCode, fSTDOUT != null ? fSTDOUT : emptyStringList, fSTDERR != null ? fSTDERR : emptyStringList);
                    if (fCommand.onCommandLineListener != null)
                        fCommand.onCommandLineListener.onCommandResult(fCommand.code, fExitCode);
                    if (fCommand.onCommandInputStreamListener != null)
                        fCommand.onCommandInputStreamListener.onCommandResult(fCommand.code, fExitCode);
                } else if (fCommand.onCommandInputStreamListener != null) {
                    fCommand.onCommandInputStreamListener.onInputStream(inputStream);
                }
                return true;
            }
            startCallback();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (inputStream == null) {
                            if (fCommand.onCommandResultListener != null)
                                fCommand.onCommandResultListener.onCommandResult(fCommand.code, fExitCode, fSTDOUT != null ? fSTDOUT : emptyStringList);
                            if (fCommand.onCommandResultListener2 != null)
                                fCommand.onCommandResultListener2.onCommandResult(fCommand.code, fExitCode, fSTDOUT != null ? fSTDOUT : emptyStringList, fSTDERR != null ? fSTDERR : emptyStringList);
                            if (fCommand.onCommandLineListener != null)
                                fCommand.onCommandLineListener.onCommandResult(fCommand.code, fExitCode);
                            if (fCommand.onCommandInputStreamListener != null)
                                fCommand.onCommandInputStreamListener.onCommandResult(fCommand.code, fExitCode);
                        } else if (fCommand.onCommandInputStreamListener != null) {
                            fCommand.onCommandInputStreamListener.onInputStream(inputStream);
                        }
                    } finally {
                        endCallback();
                    }
                }
            });
            return false;
        }

        /**
         * Decrease callback counter, signals callback complete state when
         * dropped to 0
         */
        void endCallback() {
            synchronized (callbackSync) {
                callbacks--;
                if (callbacks == 0) {
                    callbackSync.notifyAll();
                }
            }
        }

        /**
         * Internal call that launches the shell, starts gobbling, and starts
         * executing commands. See {@link Interactive}
         *
         * @return Opened successfully ?
         */
        private synchronized boolean open() {
            Debug.log(String.format(Locale.ENGLISH, "[%s%%] START", shell.toUpperCase(Locale.ENGLISH)));

            try {
                // setup our process, retrieve STDIN stream, and STDOUT/STDERR
                // gobblers
                if (environment.size() == 0) {
                    process = Runtime.getRuntime().exec(shell);
                } else {
                    Map<String, String> newEnvironment = new HashMap<String, String>();
                    newEnvironment.putAll(System.getenv());
                    newEnvironment.putAll(environment);
                    int i = 0;
                    String[] env = new String[newEnvironment.size()];
                    for (Map.Entry<String, String> entry : newEnvironment.entrySet()) {
                        env[i] = entry.getKey() + "=" + entry.getValue();
                        i++;
                    }
                    process = Runtime.getRuntime().exec(shell, env);
                }

                // this should never actually happen
                if (process == null) throw new NullPointerException();

                OnStreamClosedListener onStreamClosedListener = new OnStreamClosedListener() {
                    @Override
                    public void onStreamClosed() {
                        if (shellDiesOnSTDOUTERRClose || !isRunning()) {
                            if ((STDERR != null) && (Thread.currentThread() == STDOUT)) STDERR.resumeGobbling();
                            if ((STDOUT != null) && (Thread.currentThread() == STDERR)) STDOUT.resumeGobbling();

                            boolean isLast;
                            synchronized (STDclosedSync){
                                if (Thread.currentThread() == STDOUT) STDOUTclosed = true;
                                if (Thread.currentThread() == STDERR) STDERRclosed = true;
                                isLast = STDOUTclosed && STDERRclosed;

                                Command c = command;
                                if (c != null) {
                                    MarkerInputStream mis = c.markerInputStream;
                                    if (mis != null) {
                                        mis.setEOF();
                                    }
                                }
                            }

                            if (isLast) { // make sure both are done
                                waitForCallbacks();

                                synchronized (Interactive.this) {
                                    // our shell died for unknown reasons - abort all submissions
                                    if (command != null) {
                                        //noinspection ConstantConditions // all writes to 'command' are synchronized
                                        postCallback(command, OnResult.SHELL_DIED, bufferSTDOUT, bufferSTDERR, null);
                                        command = null;
                                    }
                                    closed = true;
                                    opening = false;
                                    runNextCommand();
                                }
                            }
                        }
                    }
                };

                STDIN = new DataOutputStream(process.getOutputStream());
                STDOUT = new StreamGobbler(shell.toUpperCase(Locale.ENGLISH) + "-",
                        process.getInputStream(), new OnLineListener() {
                    @SuppressWarnings("ConstantConditions") // all writes to 'command' are synchronized
                    @Override
                    public void onLine(@NonNull String line) {
                        Command cmd = command;
                        if ((cmd != null) && (cmd.onCommandInputStreamListener != null)) {
                            // we need to suspend the normal input reader
                            if (line.equals("inputstream")) {
                                if (STDOUT != null) STDOUT.suspendGobbling();
                                return;
                            }
                        }

                        synchronized (Interactive.this) {
                            if (command == null) {
                                return;
                            }

                            String contentPart = line;
                            String markerPart = null;

                            int markerIndex = line.indexOf(command.marker);
                            if (markerIndex == 0) {
                                contentPart = null;
                                markerPart = line;
                            } else if (markerIndex > 0) {
                                contentPart = line.substring(0, markerIndex);
                                markerPart = line.substring(markerIndex);
                            }

                            if (contentPart != null) {
                                addBuffer(contentPart, false);
                                processLine(contentPart, onSTDOUTLineListener, false);
                                processLine(contentPart, command.onCommandLineListener, false);
                            }

                            if (markerPart != null) {
                                try {
                                    lastExitCode = Integer.valueOf(
                                            markerPart.substring(command.marker.length() + 1), 10);
                                } catch (Exception e) {
                                    // this really shouldn't happen
                                    e.printStackTrace();
                                }
                                lastMarkerSTDOUT = command.marker;
                                processMarker();
                            }
                        }
                    }
                }, onStreamClosedListener);
                STDERR = new StreamGobbler(shell.toUpperCase(Locale.ENGLISH) + "*",
                        process.getErrorStream(), new OnLineListener() {
                    @SuppressWarnings("ConstantConditions") // all writes to 'command' are synchronized
                    @Override
                    public void onLine(@NonNull String line) {
                        synchronized (Interactive.this) {
                            if (command == null) {
                                return;
                            }

                            String contentPart = line;

                            int markerIndex = line.indexOf(command.marker);
                            if (markerIndex == 0) {
                                contentPart = null;
                            } else if (markerIndex > 0) {
                                contentPart = line.substring(0, markerIndex);
                            }

                            if (contentPart != null) {
                                addBuffer(contentPart, true);
                                processLine(contentPart, onSTDERRLineListener, true);
                                processLine(contentPart, command.onCommandLineListener, true);
                                processLine(contentPart, command.onCommandInputStreamListener, true);
                            }

                            if (markerIndex >= 0) {
                                lastMarkerSTDERR = command.marker;
                                processMarker();
                            }
                        }
                    }
                }, onStreamClosedListener);

                // start gobbling and write our commands to the shell
                STDOUT.start();
                STDERR.start();

                running = true;
                closed = false;

                runNextCommand();

                return true;
            } catch (IOException e) {
                // shell probably not found
                return false;
            }
        }

        /**
         * Currently unused. May be called multiple times for each actual close.
         */
        protected void onClosed() {
            // callbacks may still be scheduled/running at this point, and this may be called
            // multiple times!
            // if (inClosingJoin) return; // prevent deadlock, we will be called after
        }

        /**
         * Currently redirects to {@link #closeImmediately()}. You should use
         * {@link #closeImmediately()} or {@link #closeWhenIdle()} directly instead
         */
        // not annotated @deprecated because we do want to use this method in Threaded
        @WorkerThread // if shell not idle
        public void close() {
            closeImmediately();
        }

        /**
         * Close shell and clean up all resources. Call this when you are done
         * with the shell. If the shell is not idle (all commands completed) you
         * should not call this method from the main UI thread because it may
         * block for a long time. This method will intentionally crash your app
         * (if in debug mode) if you try to do this anyway.
         */
        @WorkerThread // if shell not idle
        public void closeImmediately() {
            closeImmediately(false);
        }

        protected void closeImmediately(boolean fromIdle) {
            // these should never happen, satisfy lint
            if ((STDIN == null) || (STDOUT == null) || (STDERR == null) || (process == null)) throw new NullPointerException();

            boolean _idle = isIdle(); // idle must be checked synchronized

            synchronized (this) {
                if (!running)
                    return;
                running = false;
                closed = true;
            }

            if (!isRunning()) {
                onClosed();
                return;
            }

            // This method should not be called from the main thread unless the
            // shell is idle and can be cleaned up with (minimal) waiting. Only
            // throw in debug mode.
            if (!_idle && Debug.getSanityChecksEnabledEffective() && Debug.onMainThread()) {
                Debug.log(ShellOnMainThreadException.EXCEPTION_NOT_IDLE);
                throw new ShellOnMainThreadException(ShellOnMainThreadException.EXCEPTION_NOT_IDLE);
            }

            if (!_idle)
                waitForIdle();

            try {
                try {
                    STDIN.write(("exit\n").getBytes("UTF-8"));
                    STDIN.flush();
                } catch (IOException e) {
                    if (e.getMessage().contains("EPIPE") || e.getMessage().contains("Stream closed")) {
                        // we're not running a shell, the shell closed STDIN,
                        // the script already contained the exit command, etc.                        
                    } else {
                        throw e;
                    }
                }

                // wait for our process to finish, while we gobble away in the
                // background
                process.waitFor();

                // make sure our threads are done gobbling, our streams are
                // closed, and the process is destroyed - while the latter two
                // shouldn't be needed in theory, and may even produce warnings,
                // in "normal" Java they are required for guaranteed cleanup of
                // resources, so lets be safe and do this on Android as well
                try {
                    STDIN.close();
                } catch (IOException e) {
                    // STDIN going missing is no reason to abort 
                }

                // Make sure our threads our running
                if (Thread.currentThread() != STDOUT) STDOUT.resumeGobbling();
                if (Thread.currentThread() != STDERR) STDERR.resumeGobbling();

                // Otherwise we may deadlock waiting on eachother, happens when this is run from OnShellOpenResultListener
                if ((Thread.currentThread() != STDOUT) && (Thread.currentThread() != STDERR)) {
                    inClosingJoin = true;
                    STDOUT.conditionalJoin();
                    STDERR.conditionalJoin();
                    inClosingJoin = false;
                }

                stopWatchdog();
                process.destroy();
            } catch (IOException e) {
                // various unforseen IO errors may still occur
            } catch (InterruptedException e) {
                // this should really be re-thrown
            }

            Debug.log(String.format(Locale.ENGLISH, "[%s%%] END", shell.toUpperCase(Locale.ENGLISH)));

            onClosed();
        }

        /**
         * {@link #close()} the shell when it becomes idle. Note that in contrast to
         * {@link #closeImmediately()}, this method does <i>not</i> block until the
         * shell is closed!
         */
        @AnyThread
        public void closeWhenIdle() {
            if (idle) {
                closeImmediately(true);
            } else {
                doCloseWhenIdle = true;
            }
        }

        /**
         * Try to clean up as much as possible from a shell that's gotten itself
         * wedged. Hopefully the StreamGobblers will croak on their own when the
         * other side of the pipe is closed.
         */
        @WorkerThread
        public synchronized void kill() {
            // these should never happen, satisfy lint
            if ((STDIN == null) || (process == null)) throw new NullPointerException();

            running = false;
            closed = true;

            try {
                STDIN.close();
            } catch (IOException e) {
                // in case it was closed
            }
            try {
                process.destroy();
            } catch (Exception e) {
                // in case it was already destroyed or can't be
            }

            idle = true;
            opening = false;
            synchronized (idleSync) {
                idleSync.notifyAll();
            }
            if (lastOpening && !opening) {
                lastOpening = opening;
                synchronized (openingSync) {
                    openingSync.notifyAll();
                }
            }

            onClosed();
        }

        /**
         * <p>
         * Is our shell currently being opened ?
         * </p>
         * <p>
         * Requires OnShellOpenResultCallback to be used when opening, or
         * {@link Builder#setDetectOpen(boolean)} to be true
         * </p>
         *
         * @return Shell opening ?
         */
        @AnyThread
        public boolean isOpening() {
            return isRunning() && opening;
        }

        /**
         * Is our shell still running ?
         *
         * @return Shell running ?
         */
        @AnyThread
        public boolean isRunning() {
            if (process == null) {
                return false;
            }
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException e) {
                // if this is thrown, we're still running
            }
            return true;
        }

        /**
         * Have all commands completed executing ?
         *
         * @return Shell idle ?
         */
        @AnyThread
        public synchronized boolean isIdle() {
            if (!isRunning()) {
                idle = true;
                opening = false;
                synchronized (idleSync) {
                    idleSync.notifyAll();
                }
                if (lastOpening && !opening) {
                    lastOpening = opening;
                    synchronized (openingSync) {
                        openingSync.notifyAll();
                    }
                }
            }
            return idle;
        }

        private boolean waitForCallbacks() {
            if ((handler != null) &&
                    (handler.getLooper() != null) &&
                    (handler.getLooper() != Looper.myLooper())) {
                // If the callbacks are posted to a different thread than
                // this one, we can wait until all callbacks have called
                // before returning. If we don't use a Handler at all, the
                // callbacks are already called before we get here. If we do
                // use a Handler but we use the same Looper, waiting here
                // would actually block the callbacks from being called

                synchronized (callbackSync) {
                    while (callbacks > 0) {
                        try {
                            callbackSync.wait();
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /**
         * <p>
         * Wait for idle state. As this is a blocking call, you should not call
         * it from the main UI thread. If you do so and debug mode is enabled,
         * this method will intentionally crash your app.
         * </p>
         * <p>
         * If not interrupted, this method will not return until all commands
         * have finished executing. Note that this does not necessarily mean
         * that all the callbacks have fired yet.
         * </p>
         * <p>
         * If no Handler is used, all callbacks will have been executed when
         * this method returns. If a Handler is used, and this method is called
         * from a different thread than associated with the Handler's Looper,
         * all callbacks will have been executed when this method returns as
         * well. If however a Handler is used but this method is called from the
         * same thread as associated with the Handler's Looper, there is no way
         * to know.
         * </p>
         * <p>
         * In practice this means that in most simple cases all callbacks will
         * have completed when this method returns, but if you actually depend
         * on this behavior, you should make certain this is indeed the case.
         * </p>
         * <p>
         * See {@link Interactive} for further details on threading and
         * handlers
         * </p>
         *
         * @return True if wait complete, false if wait interrupted
         */
        @WorkerThread
        public boolean waitForIdle() {
            if (Debug.getSanityChecksEnabledEffective() && Debug.onMainThread()) {
                Debug.log(ShellOnMainThreadException.EXCEPTION_WAIT_IDLE);
                throw new ShellOnMainThreadException(ShellOnMainThreadException.EXCEPTION_WAIT_IDLE);
            }

            if (isRunning()) {
                synchronized (idleSync) {
                    while (!idle) {
                        try {
                            idleSync.wait();
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }

                return waitForCallbacks();
            }

            return true;
        }

        /**
         * <p>
         * Wait for shell opening to complete
         * </p>
         * <p>
         * Requires OnShellOpenResultCallback to be used when opening, or
         * {@link Builder#setDetectOpen(boolean)} to be true
         * </p>
         *
         * @param defaultIfInterrupted What to return if an interrupt occurs, null to keep waiting
         * @return If shell was opened successfully
         */
        @WorkerThread
        public boolean waitForOpened(@Nullable Boolean defaultIfInterrupted) {
            if (Debug.getSanityChecksEnabledEffective() && Debug.onMainThread()) {
                Debug.log(ShellOnMainThreadException.EXCEPTION_WAIT_IDLE);
                throw new ShellOnMainThreadException(ShellOnMainThreadException.EXCEPTION_WAIT_IDLE);
            }

            if (isRunning()) {
                synchronized (openingSync) {
                    while (opening) {
                        try {
                            openingSync.wait();
                        } catch (InterruptedException e) {
                            if (defaultIfInterrupted != null) {
                                return defaultIfInterrupted;
                            }
                        }
                    }
                }
            }

            return isRunning();
        }

        /**
         * Are we using a Handler to post callbacks ?
         *
         * @return Handler used ?
         */
        @AnyThread
        public boolean hasHandler() {
            return (handler != null);
        }

        /**
         * Are there any commands scheduled ?
         *
         * @return Commands scheduled ?
         */
        @AnyThread
        public boolean hasCommands() {
            return (commands.size() > 0);
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands) throws ShellDiedException {
            return run(commands, null, null, false);
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands, @Nullable final List<String> STDOUT, @Nullable final List<String> STDERR, boolean clear) throws ShellDiedException {
            if (clear) {
                if (STDOUT != null) STDOUT.clear();
                if (STDERR != null) STDERR.clear();
            }
            final int[] exitCode = new int[1];
            addCommand(commands, 0, new OnCommandResultListener2() {
                @Override
                public void onCommandResult(int commandCode, int intExitCode, @NonNull List<String> intSTDOUT, @NonNull List<String> intSTDERR) {
                    exitCode[0] = intExitCode;
                    if (STDOUT != null) STDOUT.addAll(intSTDOUT);
                    if (STDERR != null) STDERR.addAll(intSTDERR);
                }
            });
            waitForIdle();
            if (exitCode[0] < 0) throw new ShellDiedException();
            return exitCode[0];
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands, @NonNull final OnSyncCommandLineListener onSyncCommandLineListener) throws ShellDiedException {
            final int[] exitCode = new int[1];
            addCommand(commands, 0, new OnCommandLineListener() {
                @Override
                public void onSTDERR(@NonNull String line) {
                    onSyncCommandLineListener.onSTDERR(line);
                }

                @Override
                public void onSTDOUT(@NonNull String line) {
                    onSyncCommandLineListener.onSTDOUT(line);
                }

                @Override
                public void onCommandResult(int commandCode, int intExitCode) {
                    exitCode[0] = intExitCode;
                }
            });
            waitForIdle();
            if (exitCode[0] < 0) throw new ShellDiedException();
            return exitCode[0];
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands, @NonNull final OnSyncCommandInputStreamListener onSyncCommandInputStreamListener) throws ShellDiedException {
            final int[] exitCode = new int[1];
            addCommand(commands, 0, new OnCommandInputStreamListener() {
                @Override
                public void onSTDERR(@NonNull String line) {
                    onSyncCommandInputStreamListener.onSTDERR(line);
                }

                @Override
                public void onInputStream(@NonNull InputStream inputStream) {
                    onSyncCommandInputStreamListener.onInputStream(inputStream);
                }

                @Override
                public void onCommandResult(int commandCode, int intExitCode) {
                    exitCode[0] = intExitCode;
                }
            });
            waitForIdle();
            if (exitCode[0] < 0) throw new ShellDiedException();
            return exitCode[0];
        }
    }

    /**
     * <p>
     * Variant of {@link Interactive} that uses a dedicated background thread for callbacks,
     * rather than requiring the library users to manage this themselves. It also provides
     * support for pooling, and is used by {@link Pool}
     * </p>
     * <p>
     * While {@link Interactive}'s asynchronous calls are relatively easy to use from the
     * main UI thread, many developers struggle with implementing it correctly in background
     * threads. This class is a one-stop solution for those issues
     * </p>
     * <p>
     * You can use this class from the main UI thread as well, though you should take note
     * that since the callbacks are run in a background thread, you cannot manipulate the
     * UI directly. You can use the Activity#runOnUiThread() to work around this
     * </p>
     * <p>
     * Please note that the {@link #close()} method behaves differently from the implementation
     * in {@link Interactive}!
     * </p>
     *
     * @see Interactive
     */
    public static class Threaded extends Interactive {
        private static int threadCounter = 0;
        private static int incThreadCounter() {
            synchronized (Threaded.class) {
                int ret = threadCounter;
                threadCounter++;
                return ret;
            }
        }

        @NonNull
        private final HandlerThread handlerThread;
        private final boolean pooled;
        private final Object onCloseCalledSync = new Object();
        private volatile boolean onClosedCalled = false;
        private final Object onPoolRemoveCalledSync = new Object();
        private volatile boolean onPoolRemoveCalled = false;
        private volatile boolean reserved = true;
        private volatile boolean closeEvenIfPooled = false;

        private static Handler createHandlerThread() {
            // to work-around having to call super() as first line in constructor, but still
            // being able to keep fields final
            HandlerThread handlerThread = new HandlerThread("Shell.Threaded#" + incThreadCounter());
            handlerThread.start();
            return new Handler(handlerThread.getLooper());
        }

        /**
         * The only way to create an instance: Shell.Builder::openThreaded(...)
         *
         * @see Shell.Builder#openThreaded()
         * @see Shell.Builder#openThreaded(Shell.OnShellOpenResultListener)
         *
         * @param builder Builder class to take values from
         * @param onShellOpenResultListener Callback
         * @param pooled Will this instance be pooled ?
         */
        protected Threaded(Builder builder, OnShellOpenResultListener onShellOpenResultListener, boolean pooled) {
            super(builder.setHandler(createHandlerThread()).
                    setDetectOpen(true).
                    setShellDiesOnSTDOUTERRClose(true),
                    onShellOpenResultListener);

            // don't try this at home
            //noinspection ConstantConditions
            handlerThread = (HandlerThread)handler.getLooper().getThread();

            // it's ok if close is called before this
            this.pooled = pooled;
            if (this.pooled) protect();
        }

        @Override
        protected void finalize() throws Throwable {
            if (pooled) closed = true; // prevent ShellNotClosedException exception on pool
            super.finalize();
        }

        private void protect() {
            synchronized (onCloseCalledSync) {
                if (!onClosedCalled) {
                    Garbage.protect(this);
                }
            }
        }

        private void collect() {
            Garbage.collect(this);
        }

        /**
         * <p>
         * Redirects to non-blocking {@link #closeWhenIdle()} if this instance is not part of a
         * pool; if it is, returns the instance to the pool
         * </p>
         * <p>
         * Note that this behavior is different from the superclass' behavior, which redirects
         * to the blocking {@link #closeImmediately()}
         * </p>
         * <p>
         * This change in behavior between super- and subclass is a clear code smell, but it is
         * needed to support AutoCloseable, makes the flow with {@link Pool} better, and maintains
         * compatibility with older code using this library (which there is quite a bit of)
         * </p>
         */
        @Override
        @AnyThread
        public void close() {
            protect();

            // NOT close(Immediately), but closeWhenIdle, note!
            if (pooled) {
                super.closeWhenIdle();
            } else {
                closeWhenIdle();
            }
        }

        @Override
        protected void closeImmediately(boolean fromIdle) {
            protect();

            if (pooled) {
                if (fromIdle) {
                    boolean callRelease = false;
                    synchronized (onPoolRemoveCalledSync) {
                        if (!onPoolRemoveCalled) {
                            callRelease = true;
                        }
                    }
                    if (callRelease) Pool.releaseReservation(this);
                    if (closeEvenIfPooled) {
                        super.closeImmediately(true);
                    }
                } else {
                    boolean callRemove = false;
                    synchronized (onPoolRemoveCalledSync) {
                        if (!onPoolRemoveCalled) {
                            onPoolRemoveCalled = true;
                            callRemove = true;
                        }
                    }
                    if (callRemove) Pool.removeShell(this);
                    super.closeImmediately(false);
                }
            } else {
                super.closeImmediately(fromIdle);
            }
        }

        private void closeWhenIdle(boolean fromPool) {
            protect();

            if (pooled) {
                synchronized (onPoolRemoveCalledSync) {
                    if (!onPoolRemoveCalled) {
                        onPoolRemoveCalled = true;
                        Pool.removeShell(this);
                    }
                }
                if (fromPool) {
                    closeEvenIfPooled = true;
                }
            }
            super.closeWhenIdle();
        }

        @Override
        @AnyThread
        public void closeWhenIdle() {
            closeWhenIdle(false);
        }

        boolean wasPoolRemoveCalled() {
            synchronized (onPoolRemoveCalledSync) {
                return onPoolRemoveCalled;
            }
        }

        @SuppressWarnings("ConstantConditions") // handler is never null
        @Override
        protected void onClosed() {
            // clean up our thread
            if (inClosingJoin) return; // prevent deadlock, we will be called after

            if (pooled) {
                boolean callRemove = false;
                synchronized (onPoolRemoveCalledSync) {
                    if (!onPoolRemoveCalled) {
                        onPoolRemoveCalled = true;
                        callRemove = true;
                    }
                }
                if (callRemove) {
                    protect();
                    Pool.removeShell(this);
                }
            }

            // we've been GC'd by removeShell above, code below should already have been executed
            if (onCloseCalledSync == null) return;

            synchronized (onCloseCalledSync) {
                if (onClosedCalled) return;
                onClosedCalled = true;
            }

            try {
                super.onClosed();
            } finally {
                if (!handlerThread.isAlive()) {
                    collect();
                } else {
                    handler.post(new Runnable() {
                        @SuppressWarnings("ConstantConditions") // handler is never null
                        @Override
                        public void run() {
                            synchronized (callbackSync) {
                                if (callbacks > 0) {
                                    // we still have some callbacks running
                                    handler.postDelayed(this, 1000);
                                } else {
                                    collect();
                                    if (Build.VERSION.SDK_INT >= 18) {
                                        handlerThread.quitSafely();
                                    } else {
                                        handlerThread.quit();
                                    }
                                }
                            }
                        }
                    });
                }
            }
        }

        /**
         * <p>
         * Cast current instance to {@link ThreadedAutoCloseable} which can be used with
         * try-with-resources
         * </p>
         * <p>
         * On API &gt;= 19, all instances are safe to cast this way, as all instances are
         * created as {@link ThreadedAutoCloseable}. On older API levels, this returns null
         * </p>
         *
         * @return ThreadedAutoCloseable on API &gt;= 19, null otherwise
         */
        @Nullable
        @AnyThread
        public ThreadedAutoCloseable ac() {
            if (this instanceof ThreadedAutoCloseable) {
                return (ThreadedAutoCloseable)this;
            } else {
                return null;
            }
        }

        private boolean isReserved() {
            return reserved;
        }

        private void setReserved(boolean reserved) {
            this.reserved = reserved;
        }
    }

    /**
     * <p>
     * AutoClosable variant of {@link Threaded} that can be used with try-with-resources
     * </p>
     * <p>
     * This class is automatically used instead of {@link Threaded} everywhere on
     * API &gt;= 19. Use {@link Threaded#ac()} to auto-cast (returns null on API &lt;= 19)
     * </p>
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static class ThreadedAutoCloseable extends Threaded implements AutoCloseable {
        protected ThreadedAutoCloseable(@NonNull Builder builder, OnShellOpenResultListener onShellOpenResultListener, boolean pooled) {
            super(builder, onShellOpenResultListener, pooled);
        }
    }

    /**
     * <p>
     * Helper class for pooled command execution
     * </p>
     * <p>
     * {@link Interactive} and {@link Threaded}'s run() methods operate on a specific
     * shell instance. This class supports the same synchronous methods, but runs them
     * on any available shell instance from the pool, creating a new instance when none
     * is available.
     * </p>
     * <p>
     * {@link Pool#SH} and {@link Pool#SH} are instances of this class, you can create
     * a wrapper for other shell commands using {@link Pool#getWrapper(String)}
     * </p>
     *
     * @see Pool
     */
    public static class PoolWrapper implements DeprecatedSyncCommands, SyncCommands {
        private final String shellCommand;

        /**
         * Constructor for {@link PoolWrapper}
         *
         * @param shell Shell command, like "sh" or "su"
         */
        @AnyThread
        public PoolWrapper(@NonNull String shell) {
            this.shellCommand = shell;
        }

        /**
         * <p>
         * Retrieves a {@link Threaded} instance from the {@link Pool}, creating a new one
         * if none are available. You <i>must</i> call {@link Threaded#close()} to return
         * the instance to the {@link Pool}
         * </p>
         * <p>
         * If called from a background thread, the shell is fully opened before this method
         * returns. If called from the main UI thread, the shell may not have completed
         * opening.
         * </p>
         *
         * @return A {@link Threaded} instance from the {@link Pool}
         * @throws ShellDiedException if a shell could not be retrieved (execution failed, access denied)
         */
        @NonNull
        @AnyThread
        public Threaded get() throws ShellDiedException {
            return Shell.Pool.get(shellCommand);
        }

        /**
         * <p>
         * Retrieves a {@link Threaded} instance from the {@link Pool}, creating a new one
         * if none are available. You <i>must</i> call {@link Threaded#close()} to return
         * the instance to the {@link Pool}
         * </p>
         * <p>
         * The callback with open status is called before this method returns if this method
         * is called from a background thread. When called from the main UI thread, the
         * method may return before the callback is executed (or the shell has completed opening)
         * </p>
         *
         * @param onShellOpenResultListener Callback to return shell open status
         * @return A {@link Threaded} instance from the {@link Pool}
         * @throws ShellDiedException if a shell could not be retrieved (execution failed, access denied)
         */
        @NonNull
        @AnyThread
        public Threaded get(@Nullable OnShellOpenResultListener onShellOpenResultListener) throws ShellDiedException {
            return Shell.Pool.get(shellCommand, onShellOpenResultListener);
        }

        /**
         * <p>
         * Retrieves a new {@link Threaded} instance that is not part of the {@link Pool}
         * </p>
         *
         * @return A {@link Threaded} instance
         */
        @NonNull
        @AnyThread
        public Threaded getUnpooled() {
            return Shell.Pool.getUnpooled(shellCommand);
        }

        /**
         * <p>
         * Retrieves a new {@link Threaded} instance that is not part of the {@link Pool}
         * </p>
         *
         * @param onShellOpenResultListener Callback to return shell open status
         * @return A {@link Threaded} instance
         */
        @NonNull
        @AnyThread
        public Threaded getUnpooled(@Nullable OnShellOpenResultListener onShellOpenResultListener) {
            return Shell.Pool.getUnpooled(shellCommand, onShellOpenResultListener);
        }

        // documented in DeprecatedSyncCommands interface
        @Nullable
        @Deprecated
        @WorkerThread
        public List<String> run(@NonNull Object commands, final boolean wantSTDERR) {
            try {
                Threaded shell = get();
                try {
                    final int[] exitCode = new int[1];
                    final List<String> output = new ArrayList<String>();
                    shell.addCommand(commands, 0, new OnCommandResultListener2() {
                        @Override
                        public void onCommandResult(int commandCode, int intExitCode, @NonNull List<String> intSTDOUT, @NonNull List<String> intSTDERR) {
                            exitCode[0] = intExitCode;
                            output.addAll(intSTDOUT);
                            if (wantSTDERR) {
                                output.addAll(intSTDERR);
                            }
                        }
                    });
                    shell.waitForIdle();
                    if (exitCode[0] < 0) return null;
                    return output;
                } finally {
                    shell.close();
                }
            } catch (ShellDiedException e) {
                return null;
            }
        }

        // documented in DeprecatedSyncCommands interface
        @Nullable
        @SuppressWarnings("unchecked") // if the user passes in List<> of anything other than String, that's on them
        @Deprecated
        @WorkerThread
        public List<String> run(@NonNull Object commands, @Nullable String[] environment, boolean wantSTDERR) {
            if (environment == null) {
                return run(commands, wantSTDERR);
            } else {
                String[] _commands;
                if (commands instanceof String) {
                    _commands = new String[] { (String)commands };
                } else if (commands instanceof List<?>) {
                    _commands = ((List<String>)commands).toArray(new String[0]);
                } else if (commands instanceof String[]) {
                    _commands = (String[])commands;
                } else {
                    throw new IllegalArgumentException("commands parameter must be of type String, List<String> or String[]");
                }

                StringBuilder sb = new StringBuilder();
                for (String entry : environment) {
                    int split;
                    if ((split = entry.indexOf("=")) >= 0) {
                        boolean quoted = entry.substring(split + 1, split + 2).equals("\"");
                        sb.append(entry, 0, split);
                        sb.append(quoted ? "=" : "=\"");
                        sb.append(entry.substring(split + 1));
                        sb.append(quoted ? " " : "\" ");
                    }
                }
                sb.append("sh -c \"\n");
                for (String line : _commands) {
                    sb.append(line);
                    sb.append("\n");
                }
                sb.append("\"");
                return run(new String[] { sb.toString().
                        replace("\\", "\\\\").
                        replace("$", "\\$")
                }, wantSTDERR);
            }
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands) throws ShellDiedException {
            return run(commands, null, null, false);
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands, @Nullable List<String> STDOUT, @Nullable List<String> STDERR, boolean clear) throws ShellDiedException {
            Threaded shell = get();
            try {
                return shell.run(commands, STDOUT, STDERR, clear);
            } finally {
                shell.close();
            }
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands, @NonNull OnSyncCommandLineListener onSyncCommandLineListener) throws ShellDiedException {
            Threaded shell = get();
            try {
                return shell.run(commands, onSyncCommandLineListener);
            } finally {
                shell.close();
            }
        }

        // documented in SyncCommands interface
        @Override
        @WorkerThread
        public int run(@NonNull Object commands, @NonNull OnSyncCommandInputStreamListener onSyncCommandInputStreamListener) throws ShellDiedException {
            Threaded shell = get();
            try {
                return shell.run(commands, onSyncCommandInputStreamListener);
            } finally {
                shell.close();
            }
        }
    }

    /**
     * <p>
     * Class that manages {@link Threaded} shell pools. When one of its (or {@link PoolWrapper}'s)
     * get() methods is used, a shell is retrieved from the pool, or a new one is created if none
     * are available. You <i>must</i> call {@link Threaded#close()} to return a shell to the pool
     * for reuse
     * </p>
     * <p>
     * While as many shells are created on-demand as necessary {@link #setPoolSize(int)} governs
     * how many open shells are kept around in the pool once they become idle. Note that this only
     * applies to "su"-based (root) shells, there is at most one instance of other shells
     * (such as "sh") kept around, based on the assumption that starting those is cheap, while
     * starting a "su"-based shell is expensive (and may interrupt the user with a permission
     * dialog)
     * </p>
     * <p>
     * If you want to change the default settings the {@link Threaded} shells are created with,
     * call {@link #setOnNewBuilderListener(OnNewBuilderListener)}. It is advised to this only
     * once, from Application::onCreate(). If you change this after shells have been created,
     * you may end up with some shells have the default settings, and others having your
     * customized ones
     * </p>
     * <p>
     * For convenience, getUnpooled() methods are also provided, creating new {@link Threaded}
     * shells you can manage on your own, but using the same {@link Builder} settings as
     * configured for the pool
     * </p>
     * <p>
     * {@link PoolWrapper} instances are setup for you already as {@link Shell.SH} and
     * {@link Shell.SU}, allowing you to call Shell.SH/SU.run(...) without further management
     * requirements. These methods retrieve an idle shell from the pool, run the passed
     * commands in synchronous fashion (throwing {@link ShellDiedException} on any issue),
     * and return the used shell to pool. Though their signatures are (intentionally) the
     * same as the run(...) methods from the {@link Threaded} class (and indeed those are
     * used internally), they should not be confused: the ones in the {@link Threaded} class
     * operate specifically on that {@link Threaded} instance, that you should have get(...)
     * before and will close() afterwards, while the {@link PoolWrapper} methods handle the
     * pooling for you
     * </p>
     * <p>
     * Should you need to pool shells that aren't "sh" or "su", a {@link PoolWrapper} instance
     * can be created for these with {@link #getWrapper(String)}
     * </p>
     *
     */
    public static class Pool {
        /**
         * Callback interface to create a {@link Builder} for new shell instances
         */
        public interface OnNewBuilderListener {
            /**
             * Called when a new {@link Builder} needs to be instantiated
             *
             * @return New {@link Builder} instance
             */
            @NonNull
            Shell.Builder newBuilder();
        }

        /**
         * Default {@link OnNewBuilderListener} interface
         *
         * @see #setOnNewBuilderListener(OnNewBuilderListener)
         */
        public static final OnNewBuilderListener defaultOnNewBuilderListener = new OnNewBuilderListener() {
            @NonNull
            @SuppressWarnings("deprecation")
            @Override
            public Shell.Builder newBuilder() {
                return (new Shell.Builder())
                        .setWantSTDERR(true)
                        .setWatchdogTimeout(0)
                        .setMinimalLogging(false);
            }
        };

        @Nullable
        private static OnNewBuilderListener onNewBuilderListener = null;
        @NonNull
        private static final Map<String, ArrayList<Threaded>> pool = new HashMap<String, ArrayList<Threaded>>();
        private static int poolSize = 4; // only applicable to su, we keep only 1 of others

        /**
         * Get the currently set {@link OnNewBuilderListener} callback. {@link #defaultOnNewBuilderListener}
         * is used when null
         *
         * @return Current {@link OnNewBuilderListener} interface, or null for {@link #defaultOnNewBuilderListener}
         */
        @Nullable
        @AnyThread
        public static synchronized OnNewBuilderListener getOnNewBuilderListener() {
            return onNewBuilderListener;
        }

        /**
         * Set current {@link OnNewBuilderListener} callback
         *
         * @param onNewBuilderListener {@link OnNewBuilderListener} to use, or null to revert to {@link #defaultOnNewBuilderListener}
         */
        @AnyThread
        public static synchronized void setOnNewBuilderListener(@Nullable OnNewBuilderListener onNewBuilderListener) {
            Pool.onNewBuilderListener = onNewBuilderListener;
        }

        /**
         * <p>
         * Retrieve current kept pool size for "su"-based (root) shells. Only one instance of
         * non-root shells is kept around long-term
         * </p>
         * <p>
         * Note that more shells may be created as needed, this number only indicates how many
         * idle instances to keep around for later use
         * </p>
         *
         * @return Current pool size
         */
        @AnyThread
        public static synchronized int getPoolSize() {
            return poolSize;
        }

        /**
         * <p>
         * Set current kept pool size for "su"-based (root) shells. Only one instance of
         * non-root shells is kept around long-term
         * </p>
         * <p>
         * Note that more shells may be created as needed, this number only indicates how many
         * idle instances to keep around for later use
         * </p>
         *
         * @param poolSize Pool size to use
         */
        @AnyThread
        public static synchronized void setPoolSize(int poolSize) {
            poolSize = Math.max(poolSize, 1);
            if (poolSize != Pool.poolSize) {
                Pool.poolSize = poolSize;
                cleanup(null, false);
            }
        }

        @NonNull
        @AnyThread
        private static synchronized Shell.Builder newBuilder() {
            if (onNewBuilderListener != null) {
                return onNewBuilderListener.newBuilder();
            } else {
                return defaultOnNewBuilderListener.newBuilder();
            }
        }

        /**
         * Retrieves a new {@link Threaded} instance that is not part of the {@link Pool}
         *
         * @param shell Shell command
         * @return A {@link Threaded}
         */
        @NonNull
        @AnyThread
        public static Threaded getUnpooled(@NonNull String shell) {
            return getUnpooled(shell, null);
        }

        /**
         * <p>
         * Retrieves a new {@link Threaded} instance that is not part of the {@link Pool},
         * with open result callback
         * </p>
         *
         * @param shell Shell command
         * @param onShellOpenResultListener Callback to return shell open status
         * @return A {@link Threaded}
         */
        @NonNull
        @AnyThread
        public static Threaded getUnpooled(@NonNull String shell, @Nullable OnShellOpenResultListener onShellOpenResultListener) {
            return newInstance(shell, onShellOpenResultListener, false);
        }

        private static Threaded newInstance(@NonNull String shell, @Nullable OnShellOpenResultListener onShellOpenResultListener, boolean pooled) {
            Debug.logPool(String.format(Locale.ENGLISH, "newInstance(shell:%s, pooled:%d)", shell, pooled ? 1 : 0));
            return newBuilder().setShell(shell).openThreadedEx(onShellOpenResultListener, pooled);
        }

        /**
         * Cleanup cycle for pooled shells
         *
         * @param toRemove Shell to remove, should already be closed, or null
         * @param removeAll Remove all shells, closing them
         */
        private static void cleanup(@Nullable Threaded toRemove, boolean removeAll) {
            String[] keySet;
            synchronized (pool) {
                keySet = pool.keySet().toArray(new String[0]);
            }
            for (String key : keySet) {
                ArrayList<Threaded> shellsModify = pool.get(key);
                if (shellsModify == null) continue;

                @SuppressWarnings("unchecked")
                ArrayList<Threaded> shellsCheck = (ArrayList<Threaded>)shellsModify.clone();
                // we use this so we don't need to synchronize the entire method, but can still
                // prevent issues by the list being modified asynchronously

                int wantedTotal = Shell.SU.isSU(key) ? poolSize : 1;
                int haveTotal = 0;
                int haveAvailable = 0;

                for (int i = shellsCheck.size() - 1; i >= 0; i--) {
                    Threaded threaded = shellsCheck.get(i);
                    if (!threaded.isRunning() || (threaded == toRemove) || removeAll) {
                        Debug.logPool("shell removed");
                        shellsCheck.remove(threaded);
                        synchronized (pool) {
                            shellsModify.remove(threaded);
                        }
                        if (removeAll) threaded.closeWhenIdle();
                    } else {
                        haveTotal += 1;
                        if (!threaded.isReserved()) {
                            haveAvailable++;
                        }
                    }
                }

                if ((haveTotal > wantedTotal) && (haveAvailable > 1)) {
                    int kill = Math.min(haveAvailable - 1, haveTotal - wantedTotal);
                    for (int i = shellsCheck.size() - 1; i >= 0; i--) {
                        Threaded threaded = shellsCheck.get(i);
                        if (!threaded.isReserved() && threaded.isIdle()) {
                            Debug.logPool("shell killed");
                            shellsCheck.remove(threaded);
                            synchronized (pool) {
                                shellsModify.remove(threaded);
                            }
                            // not calling closeImmediately() due to possible race
                            threaded.closeWhenIdle(true);
                            kill--;
                            if (kill == 0)
                                break;
                        }
                    }
                }

                synchronized (pool) {
                    if (shellsModify.size() == 0) {
                        pool.remove(key);
                    }
                }
            }

            if (Debug.getDebug()) {
                synchronized (pool) {
                    for (String key : pool.keySet()) {
                        int reserved = 0;
                        ArrayList<Threaded> shells = pool.get(key);
                        if (shells == null) continue; // never happens, satisfy lint
                        for (int i = 0; i < shells.size(); i++) {
                            if (shells.get(i).isReserved()) reserved++;
                        }
                        Debug.logPool(String.format(Locale.ENGLISH, "cleanup: shell:%s count:%d reserved:%d", key, shells.size(), reserved));
                    }
                }
            }
        }

        /**
         * <p>
         * Retrieves a {@link Threaded} instance from the {@link Pool}, creating a new one
         * if none are available. You <i>must</i> call {@link Threaded#close()} to return
         * the instance to the {@link Pool}
         * </p>
         * <p>
         * If called from a background thread, the shell is fully opened before this method
         * returns. If called from the main UI thread, the shell may not have completed
         * opening.
         * </p>
         *
         * @param shell Shell command
         * @return A {@link Threaded}
         * @throws ShellDiedException if a shell could not be retrieved (execution failed, access denied)
         */
        @NonNull
        @AnyThread
        public static Threaded get(@NonNull String shell) throws ShellDiedException {
            return get(shell, null);
        }

        /**
         * <p>
         * Retrieves a {@link Threaded} instance from the {@link Pool}, creating a new one
         * if none are available. You <i>must</i> call {@link Threaded#close()} to return
         * the instance to the {@link Pool}
         * </p>
         * <p>
         * The callback with open status is called before this method returns if this method
         * is called from a background thread. When called from the main UI thread, the
         * method may return before the callback is executed (or the shell has completed opening)
         * </p>
         *
         * @param shell Shell command
         * @param onShellOpenResultListener Callback to return shell open status
         * @return A {@link Threaded} instance from the {@link Pool}
         * @throws ShellDiedException if a shell could not be retrieved (execution failed, access denied)
         */
        @SuppressLint("WrongThread")
        @NonNull
        @AnyThread
        public static Threaded get(@NonNull String shell, @Nullable final OnShellOpenResultListener onShellOpenResultListener) throws ShellDiedException {
            Threaded threaded = null;
            String shellUpper = shell.toUpperCase(Locale.ENGLISH);

            synchronized (Pool.class) {
                cleanup(null, false);

                // find instance
                ArrayList<Threaded> shells = pool.get(shellUpper);
                if (shells != null) {
                    for (Threaded instance : shells) {
                        if (!instance.isReserved()) {
                            threaded = instance;
                            threaded.setReserved(true);
                            break;
                        }
                    }
                }
            }

            if (threaded == null) {
                // create instance
                threaded = newInstance(shell, onShellOpenResultListener, true);
                if (!threaded.isRunning()) {
                    throw new ShellDiedException();
                } else {
                    if (!(Debug.getSanityChecksEnabledEffective() && Debug.onMainThread())) {
                        if (!threaded.waitForOpened(null)) {
                            throw new ShellDiedException();
                        }
                    }
                    // otherwise failure will be in callbacks
                }
                synchronized (Pool.class) {
                    if (!threaded.wasPoolRemoveCalled()) {
                        if (pool.get(shellUpper) == null) {
                            pool.put(shellUpper, new ArrayList<Threaded>());
                        }
                        //noinspection ConstantConditions // pool.get(shellUpper) is never null here
                        pool.get(shellUpper).add(threaded);
                    }
                }
            } else {
                // shell is already open, but if an OnShellOpenResultListener was passed, call it
                if (onShellOpenResultListener != null) {
                    final Threaded fThreaded = threaded;
                    threaded.startCallback();
                    //noinspection ConstantConditions // handler is never null
                    threaded.handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                onShellOpenResultListener.onOpenResult(true, OnShellOpenResultListener.SHELL_RUNNING);
                            } finally {
                                fThreaded.endCallback();
                            }
                        }
                    });
                }
            }

            return threaded;
        }

        /**
         * @param threaded Shell to return to pool
         */
        private static synchronized void releaseReservation(@NonNull Threaded threaded) {
            Debug.logPool("releaseReservation");
            threaded.setReserved(false);
            cleanup(null, false);
        }

        /**
         * @param threaded This shell is dead
         */
        private static synchronized void removeShell(@NonNull Threaded threaded) {
            Debug.logPool("removeShell");
            cleanup(threaded, false);
        }

        /**
         * Close (as soon as they become idle) all pooled {@link Threaded} shells
         */
        @AnyThread
        public static synchronized void closeAll() {
            cleanup(null, true);
        }

        /**
         * Create a {@link PoolWrapper} for the given shell command. A returned shell is
         * automatically pooled. If the command is based on "su", {@link #getPoolSize()}
         * applies, if not, only a single instance is kept
         *
         * @param shell Shell command, like "sh" or "su"
         * @return {@link PoolWrapper} for this shell command
         */
        @AnyThread
        public static PoolWrapper getWrapper(@NonNull String shell) {
            if (shell.toUpperCase(Locale.ENGLISH).equals("SH") && (SH != null)) {
                return SH;
            } else if (shell.toUpperCase(Locale.ENGLISH).equals("SU") && (SU != null)) {
                return SU;
            } else {
                return new PoolWrapper(shell);
            }
        }

        /**
         * {@link PoolWrapper} for the "sh" shell
         */
        public static final PoolWrapper SH = getWrapper("sh");

        /**
         * {@link PoolWrapper} for the "su" (root) shell
         */
        public static final PoolWrapper SU = getWrapper("su");
    }

    /**
     * <p>
     * Helper class to prevent {@link Threaded} instances being garbage collected too soon. Not
     * reference counted, a single {@link #collect(Threaded)} call clears the protection.
     * </p>
     */
    static class Garbage {
        static final ArrayList<Threaded> shells = new ArrayList<Threaded>();

        @AnyThread
        static synchronized void protect(@NonNull Threaded shell) {
            if (shells.indexOf(shell) == -1) {
                shells.add(shell);
            }
        }

        @AnyThread
        static synchronized void collect(@NonNull Threaded shell) {
            if (shells.indexOf(shell) != -1) {
                shells.remove(shell);
            }
        }
    }
}
