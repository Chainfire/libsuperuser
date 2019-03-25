/*
 * Copyright (C) 2012-2019-2019 Jorrit "Chainfire" Jongma
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
import android.os.Handler;
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

import eu.chainfire.libsuperuser.StreamGobbler.OnLineListener;
import eu.chainfire.libsuperuser.StreamGobbler.OnStreamClosedListener;

/**
 * Class providing functionality to execute commands in a (root) shell
 */
@SuppressWarnings({"WeakerAccess", "UnusedReturnValue", "unused", "StatementWithEmptyBody"})
public class Shell {
    /**
     * <p>
     * Runs commands using the supplied shell, and returns the output, or null
     * in case of errors.
     * </p>
     * <p>
     * This method is deprecated and only provided for backwards compatibility.
     * Use {@link #run(String, String[], String[], boolean)} instead, and see
     * that same method for usage notes.
     * </p>
     *
     * @param shell The shell to use for executing the commands
     * @param commands The commands to execute
     * @param wantSTDERR Return STDERR in the output ?
     * @return Output of the commands, or null in case of an error
     */
    @Deprecated
    public static List<String> run(String shell, String[] commands, boolean wantSTDERR) {
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
     * @param shell The shell to use for executing the commands
     * @param commands The commands to execute
     * @param environment List of all environment variables (in 'key=value' format) or null for defaults
     * @param wantSTDERR  Return STDERR in the output ?
     * @return Output of the commands, or null in case of an error
     */
    public static List<String> run(String shell, String[] commands, String[] environment,
                                   boolean wantSTDERR) {
        String shellUpper = shell.toUpperCase(Locale.ENGLISH);

        if (Debug.getSanityChecksEnabledEffective() && Debug.onMainThread()) {
            // check if we're running in the main thread, and if so, crash if
            // we're in debug mode, to let the developer know attention is
            // needed here.

            Debug.log(ShellOnMainThreadException.EXCEPTION_COMMAND);
            throw new ShellOnMainThreadException(ShellOnMainThreadException.EXCEPTION_COMMAND);
        }
        Debug.logCommand(String.format("[%s%%] START", shellUpper));

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
                    Debug.logCommand(String.format("[%s+] %s", shellUpper, write));
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

        Debug.logCommand(String.format("[%s%%] END", shell.toUpperCase(Locale.ENGLISH)));
        return res;
    }

    protected static String[] availableTestCommands = new String[]{
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
    protected static boolean parseAvailableResult(List<String> ret, boolean checkForRoot) {
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
         * @param command The command to run
         * @return Output of the command, or null in case of an error
         */
        public static List<String> run(String command) {
            return Shell.run("sh", new String[]{
                    command
            }, null, false);
        }

        /**
         * Runs commands and return output
         *
         * @param commands The commands to run
         * @return Output of the commands, or null in case of an error
         */
        public static List<String> run(List<String> commands) {
            return Shell.run("sh", commands.toArray(new String[0]), null, false);
        }

        /**
         * Runs commands and return output
         *
         * @param commands The commands to run
         * @return Output of the commands, or null in case of an error
         */
        public static List<String> run(String[] commands) {
            return Shell.run("sh", commands, null, false);
        }
    }

    /**
     * This class provides utility functions to easily execute commands using SU
     * (root shell), as well as detecting whether or not root is available, and
     * if so which version.
     */
    public static class SU {
        private static Boolean isSELinuxEnforcing = null;
        private static String[] suVersion = new String[]{
                null, null
        };

        /**
         * Runs command as root (if available) and return output
         *
         * @param command The command to run
         * @return Output of the command, or null if root isn't available or in
         * case of an error
         */
        public static List<String> run(String command) {
            return Shell.run("su", new String[]{
                    command
            }, null, false);
        }

        /**
         * Runs commands as root (if available) and return output
         *
         * @param commands The commands to run
         * @return Output of the commands, or null if root isn't available or in
         * case of an error
         */
        public static List<String> run(List<String> commands) {
            return Shell.run("su", commands.toArray(new String[0]), null, false);
        }

        /**
         * Runs commands as root (if available) and return output
         *
         * @param commands The commands to run
         * @return Output of the commands, or null if root isn't available or in
         * case of an error
         */
        public static List<String> run(String[] commands) {
            return Shell.run("su", commands, null, false);
        }

        /**
         * Detects whether or not superuser access is available, by checking the
         * output of the "id" command if available, checking if a shell runs at
         * all otherwise
         *
         * @return True if superuser access available
         */
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
        public static synchronized String version(boolean internal) {
            int idx = internal ? 0 : 1;
            if (suVersion[idx] == null) {
                String version = null;

                List<String> ret = Shell.run(
                        internal ? "su -V" : "su -v",
                        new String[] { "exit" },
                        null,
                        false
                );

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

            return shell.equals("su");
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
        public static String shell(int uid, String context) {
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
        public static synchronized void clearCachedResults() {
            isSELinuxEnforcing = null;
            suVersion[0] = null;
            suVersion[1] = null;
        }
    }

    /**
     * DO NOT USE DIRECTLY. Base interface for result callbacks.
     */
    private interface OnResult {
        // for any callback
        int WATCHDOG_EXIT = -1;
        int SHELL_DIED = -2;

        // for Interactive.open() callbacks only
        int SHELL_EXEC_FAILED = -3;
        int SHELL_WRONG_UID = -4;
        int SHELL_RUNNING = 0;
    }

    /**
     * Callback for {@link Shell.Builder#open(OnShellOpenResultListener)}
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
    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    public interface OnCommandResultListener extends OnResult {
        /**
         * <p>
         * Command result callback for STDOUT, optionally interleaved with STDERR
         * </p>
         *
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         *
         * <p>
         * If wantSTDERR is set, output of STDOUT and STDERR is interleaved into
         * the output buffer. There are no guarantees of absolutely order
         * correctness (just like in a real terminal)
         * </p>
         *
         * <p>
         * To get separate STDOUT and STDERR output, use {@link OnCommandResultListener2}
         * </p>
         *
         * <p>
         * See {@link Shell.Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode Exit code of the last command in the block
         * @param output All output generated by the command block
         */
        void onCommandResult(int commandCode, int exitCode, List<String> output);
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
         *
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         *
         * <p>
         * See {@link Shell.Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode Exit code of the last command in the block
         * @param STDOUT All STDOUT output generated by the command block
         * @param STDERR All STDERR output generated by the command block
         */
        void onCommandResult(int commandCode, int exitCode, List<String> STDOUT, List<String> STDERR);
    }

    /**
     * DO NOT USE DIRECTLY. Command result callback that doesn't cause output to be buffered
     */
    private interface OnCommandResultListenerUnbuffered extends OnResult {
        /**
         * <p>
         * Command result callback
         * </p>
         *
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         *
         * <p>
         * See {@link Shell.Interactive} for threading details
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
         * <p>Line callback for STDOUT</p>
         *
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         *
         * <p>
         * See {@link Shell.Interactive} for threading details
         * </p>
         *
         * @param line One line of STDOUT output
         */
        void onSTDOUT(String line);
    } 

    /**
     * DO NOT USE DIRECTLY. Line callback for STDERR
     */
    private interface OnCommandLineSTDERR {
        /**
         * <p>Line callback for STDERR</p>
         *
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         *
         * <p>
         * See {@link Shell.Interactive} for threading details
         * </p>
         *
         * @param line One line of STDERR output
         */
        void onSTDERR(String line);
    } 

    /**
     * Command per line callback for parsing the output line by line without
     * buffering. It also notifies the recipient of the completion of a command
     * block, including the (last) exit code.
     */
    public interface OnCommandLineListener extends OnCommandResultListenerUnbuffered, OnCommandLineSTDOUT, OnCommandLineSTDERR {
    }

    /**
     * Command InputStream callback for direct access to STDOUT. It also notifies the
     * recipient of the completion of a command block, including the (last) exit code.
     */
    public interface OnCommandInputStreamListener extends OnCommandResultListenerUnbuffered, OnCommandLineSTDERR {
        /**
         * <p>
         * InputStream callback
         * </p>
         *
         * <p>
         * The read() methods will return -1 when all input is consumed, and throw an
         * IOException if the shell died before all data being read.
         * </p>
         *
         * <p>
         * If a Handler is <i>not</i> setup, this callback may be executed on one of the
         * gobbler threads. In that case, it is important the callback returns as quickly
         * as possible, as delays in this callback may pause the native process or even
         * result in a deadlock. It may also be executed on the main thread, in which
         * case you should offload handling to a different thread
         * </p>
         *
         * <p>
         * If a Handler <i>is</i> setup and it executes callbacks on the main thread,
         * you <i>should</i> offload handling to a different thread, as reading from
         * the InputStream would block your UI
         * </p>
         *
         * <p>
         * You <i>must</i> drain the InputStream (read until it returns -1 or throws
         * an IOException), otherwise execution of root commands will not continue.
         * This cannot be solved automatically without keeping it safe to offload
         * the InputStream to another thread.
         * </p>
         *
         * @param inputStream InputStream to read from
         */
        void onInputStream(InputStream inputStream);
    }

    /**
     * Internal class to store command block properties
     */
    @SuppressWarnings({"DeprecatedIsStillUsed", "deprecation"})
    private static class Command {
        private static int commandCounter = 0;

        private final String[] commands;
        private final int code;
        private final OnCommandResultListener onCommandResultListener;
        private final OnCommandResultListener2 onCommandResultListener2;
        private final OnCommandLineListener onCommandLineListener;
        private final OnCommandInputStreamListener onCommandInputStreamListener;
        private final String marker;

        private volatile MarkerInputStream markerInputStream = null;

        public Command(String[] commands, int code, OnResult listener) {
            this.commands = commands;
            this.code = code;
            this.marker = UUID.randomUUID().toString() + String.format("-%08x", ++commandCounter);

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
     * Builder class for {@link Shell.Interactive}
     */
    public static class Builder {
        private Handler handler = null;
        private boolean autoHandler = true;
        private String shell = "sh";
        private boolean wantSTDERR = false;
        private boolean shellDiesOnSTDOUTERRClose = true;
        private boolean detectOpen = true;
        private List<Command> commands = new LinkedList<Command>();
        private Map<String, String> environment = new HashMap<String, String>();
        private OnLineListener onSTDOUTLineListener = null;
        private OnLineListener onSTDERRLineListener = null;
        private int watchdogTimeout = 0;

        /**
         * <p>
         * Set a custom handler that will be used to post all callbacks to
         * </p>
         * <p>
         * See {@link Shell.Interactive} for further details on threading and
         * handlers
         * </p>
         *
         * @param handler Handler to use
         * @return This Builder object for method chaining
         */
        public Builder setHandler(Handler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * <p>
         * Automatically create a handler if possible ? Default to true
         * </p>
         * <p>
         * See {@link Shell.Interactive} for further details on threading and
         * handlers
         * </p>
         *
         * @param autoHandler Auto-create handler ?
         * @return This Builder object for method chaining
         */
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
        public Builder setShell(String shell) {
            this.shell = shell;
            return this;
        }

        /**
         * Convenience function to set "sh" as used shell
         *
         * @return This Builder object for method chaining
         */
        public Builder useSH() {
            return setShell("sh");
        }

        /**
         * Convenience function to set "su" as used shell
         *
         * @return This Builder object for method chaining
         */
        public Builder useSU() {
            return setShell("su");
        }

        /**
         * <p>
         * Detect whether the shell was opened correctly ?
         * </p>
         *
         * <p>
         * When active, this runs test commands in the shell
         * before it runs your own commands to determine if
         * the shell is functioning correctly. This is also
         * required for the {@link Interactive#isOpening()}
         * method to return a proper result
         * </p>
         *
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
        @Deprecated
        public Builder setDetectOpen(boolean detectOpen) {
            this.detectOpen = detectOpen;
            return this;
        }

        /**
         * <p>
         * Treat STDOUT/STDERR close as shell death ?
         * </p>
         *
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
        @Deprecated
        public Builder setShellDiesOnSTDOUTERRClose(boolean shellDies) {
            this.shellDiesOnSTDOUTERRClose = shellDies;
            return this;
        }

        /**
         * <p>
         * Set if STDERR output should be interleaved with STDOUT output (only) when {@link OnCommandResultListener} is used
         * </p>
         *
         * <p>
         * If you want separate STDOUT and STDERR output, use {@link OnCommandResultListener2} instead
         * </p>
         *
         * @deprecated You probably want to use {@link OnCommandResultListener2}, which ignores this setting
         *
         * @param wantSTDERR Want error output ?
         * @return This Builder object for method chaining
         */
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
        public Builder addEnvironment(String key, String value) {
            environment.put(key, value);
            return this;
        }

        /**
         * Add or update environment variables
         *
         * @param addEnvironment Map of environment variables
         * @return This Builder object for method chaining
         */
        public Builder addEnvironment(Map<String, String> addEnvironment) {
            environment.putAll(addEnvironment);
            return this;
        }

        /**
         * Add command to execute, without a callback
         *
         * @param command Command to execute
         * @return This Builder object for method chaining
         */
        public Builder addCommand(String command) {
            return addCommand(new String[] { command });
        }

        /**
         * Add commands to execute, without a callback
         *
         * @param commands Commands to execute
         * @return This Builder object for method chaining
         */
        public Builder addCommand(List<String> commands) {
            return addCommand(commands.toArray(new String[0]));
        }

        /**
         * Add commands to execute, without a callback
         *
         * @param commands Commands to execute
         * @return This Builder object for method chaining
         */
        public Builder addCommand(String[] commands) {
            return addCommand(commands, 0, null);
        }

        /**
         * Add commands to execute with a callback. See {@link #addCommand(String[], int, OnResult)}
         * for details
         *
         * @see #addCommand(String[], int, OnResult)
         *
         * @param command Command to execute
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         * @return This Builder object for method chaining
         */
        public Builder addCommand(String command, int code, OnResult onResultListener) {
            return addCommand(new String[] { command }, code, onResultListener);
        }

        /**
         * Add commands to execute with a callback. See {@link #addCommand(String[], int, OnResult)}
         * for details
         *
         * @see #addCommand(String[], int, OnResult)
         *
         * @param commands Commands to execute
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         * @return This Builder object for method chaining
         */
        public Builder addCommand(List<String> commands, int code, OnResult onResultListener) {
            return addCommand(commands.toArray(new String[0]), code, onResultListener);
        }

        /**
         * <p>
         * Add commands to execute, with a callback. Several callback interfaces are supported
         * </p>
         *
         * <p>
         * {@link OnCommandResultListener2}: provides only a callback with the result of the entire
         * command and the (last) exit code. The results are buffered until command completion, so
         * commands that generate massive amounts of output should use {@link OnCommandLineListener}
         * instead.
         * </p>
         *
         * <p>
         * {@link OnCommandLineListener}: provides a per-line callback without internal buffering.
         * Also provides a command completion callback with the (last) exit code.
         * </p>
         *
         * <p>
         * {@link OnCommandInputStreamListener}: provides a callback that is called with an
         * InputStream you can read STDOUT from directly. Also provides a command completion
         * callback with the (last) exit code. Note that this callback ignores the watchdog.
         * </p>
         *
         * <p>
         * The thread on which the callback executes is dependent on various
         * factors, see {@link Shell.Interactive} for further details
         * </p>
         *
         * @param commands Commands to execute
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         * @return This Builder object for method chaining
         */
        public Builder addCommand(String[] commands, int code, OnResult onResultListener) {
            this.commands.add(new Command(commands, code, onResultListener));
            return this;
        }

        /**
         * <p>
         * Set a callback called for every line output to STDOUT by the shell
         * </p>
         * <p>
         * The thread on which the callback executes is dependent on various
         * factors, see {@link Shell.Interactive} for further details
         * </p>
         *
         * @param onLineListener Callback to be called for each line
         * @return This Builder object for method chaining
         */
        public Builder setOnSTDOUTLineListener(OnLineListener onLineListener) {
            this.onSTDOUTLineListener = onLineListener;
            return this;
        }

        /**
         * <p>
         * Set a callback called for every line output to STDERR by the shell
         * </p>
         * <p>
         * The thread on which the callback executes is dependent on various
         * factors, see {@link Shell.Interactive} for further details
         * </p>
         *
         * @param onLineListener Callback to be called for each line
         * @return This Builder object for method chaining
         */
        public Builder setOnSTDERRLineListener(OnLineListener onLineListener) {
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
        public Builder setMinimalLogging(boolean useMinimal) {
            Debug.setLogTypeEnabled(Debug.LOG_COMMAND | Debug.LOG_OUTPUT, !useMinimal);
            return this;
        }

        /**
         * Construct a {@link Shell.Interactive} instance, and start the shell
         *
         * @return Interactive shell
         */
        public Interactive open() {
            return new Interactive(this, null);
        }

        /**
         * Construct a {@link Shell.Interactive} instance, try to start the
         * shell, and call onShellOpenResultListener to report success or failure
         *
         * @param onShellOpenResultListener Callback to return shell open status
         * @return Interactive shell
         */
        public Interactive open(OnShellOpenResultListener onShellOpenResultListener) {
            return new Interactive(this, onShellOpenResultListener);
        }
    }

    /**
     * <p>
     * An interactive shell - initially created with {@link Shell.Builder} -
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
     * {@link Shell.Builder#setHandler(Handler)} if needed. If you do not supply
     * a custom Handler - unless you set
     * {@link Shell.Builder#setAutoHandler(boolean)} to false - a Handler will
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
     * {@link Shell.Builder#open()} from the main thread, a handler will (by
     * default) be auto-created, and all the callbacks will be called on the
     * main thread. While this is often convenient and easy to code with, you
     * should be aware that if your callbacks are 'expensive' to execute, this
     * may negatively impact UI performance.
     * </p>
     * <p>
     * Background threads usually do <em>not</em> have a Looper, so calling
     * {@link Shell.Builder#open()} from such a background thread will (by
     * default) result in all the callbacks being executed in one of the gobbler
     * threads. You will have to make sure the code you execute in these
     * callbacks is thread-safe.
     * </p>
     */
    public static class Interactive {
        private final Handler handler;
        private final boolean autoHandler;
        private final String shell;
        private boolean shellDiesOnSTDOUTERRClose;
        private final boolean wantSTDERR;
        private final List<Command> commands;
        private final Map<String, String> environment;
        private final OnLineListener onSTDOUTLineListener;
        private final OnLineListener onSTDERRLineListener;
        private int watchdogTimeout;

        private Process process = null;
        private DataOutputStream STDIN = null;
        private StreamGobbler STDOUT = null;
        private StreamGobbler STDERR = null;
        private ScheduledThreadPoolExecutor watchdog = null;

        private volatile boolean running = false;
        private volatile boolean opening = false;
        private volatile boolean idle = true; // read/write only synchronized
        private volatile boolean closed = true;
        private volatile int callbacks = 0;
        private volatile int watchdogCount;
        private volatile boolean doCloseWhenIdle = false;

        private final Object idleSync = new Object();
        private final Object callbackSync = new Object();
        private final List<String> emptyStringList = new ArrayList<String>();

        private volatile int lastExitCode = 0;
        private volatile String lastMarkerSTDOUT = null;
        private volatile String lastMarkerSTDERR = null;
        private volatile Command command = null;
        private volatile List<String> bufferSTDOUT = null;
        private volatile List<String> bufferSTDERR = null;

        /**
         * The only way to create an instance: Shell.Builder::open()
         *
         * @param builder Builder class to take values from
         */
        protected Interactive(final Builder builder,
                              final OnShellOpenResultListener onShellOpenResultListener) {
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
                opening = true;

                // Allow up to 60 seconds for SuperSU/Superuser dialog, then enable
                // the user-specified timeout for all subsequent operations
                watchdogTimeout = 60;
                commands.add(0, new Command(Shell.availableTestCommands, 0, new OnCommandResultListener2() {
                    @Override
                    public void onCommandResult(int commandCode, int exitCode, List<String> STDOUT, List<String> STDERR) {
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
                            close(); // triggers SHELL_DIED on remaining commands
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
                if (hasHandler()) {
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
         * Add command to execute, without a callback
         *
         * @param command Command to execute
         */
        public synchronized void addCommand(String command) {
            addCommand(new String[] { command });
        }

        /**
         * Add commands to execute, without a callback
         *
         * @param commands Commands to execute
         */
        public synchronized void addCommand(List<String> commands) {
            addCommand(commands.toArray(new String[0]));
        }

        /**
         * Add commands to execute, without a callback
         *
         * @param commands Commands to execute
         */
        public synchronized void addCommand(String[] commands) {
            addCommand(commands, 0, null);
        }

        /**
         * Add command to execute with a callback. See {@link Shell.Builder#addCommand(String[], int, OnResult)}
         * for details
         *
         * @see Shell.Builder#addCommand(String[], int, OnResult)
         *
         * @param command Command to execute
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         */
        public synchronized void addCommand(String command, int code, OnResult onResultListener) {
            addCommand(new String[] { command }, code, onResultListener);
        }

        /**
         * Add commands to execute with a callback. See {@link Shell.Builder#addCommand(String[], int, OnResult)}
         * for details
         *
         * @see Shell.Builder#addCommand(String[], int, OnResult)
         *
         * @param commands Commands to execute
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         */
        public synchronized void addCommand(List<String> commands, int code, OnResult onResultListener) {
            addCommand(commands.toArray(new String[0]), code, onResultListener);
        }

        /**
         * Add commands to execute with a callback. See {@link Shell.Builder#addCommand(String[], int, OnResult)}
         * for details
         *
         * @see Shell.Builder#addCommand(String[], int, OnResult)
         *
         * @param commands Commands to execute
         * @param code User-defined value passed back to the callback
         * @param onResultListener One of OnCommandResultListener, OnCommandLineListener, OnCommandInputStreamListener
         */
        public synchronized void addCommand(String[] commands, int code, OnResult onResultListener) {
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
                Debug.log(String.format("[%s%%] SHELL_DIED", shell.toUpperCase(Locale.ENGLISH)));
            } else if (watchdogCount++ < watchdogTimeout) {
                return;
            } else {
                exitCode = OnResult.WATCHDOG_EXIT;
                Debug.log(String.format("[%s%%] WATCHDOG_EXIT", shell.toUpperCase(Locale.ENGLISH)));
            }

            postCallback(command, exitCode, bufferSTDOUT, bufferSTDERR, null);

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
            if (!running)
                idle = true;
                opening = false;

            if (running && !closed && idle && (commands.size() > 0)) {
                Command command = commands.get(0);
                commands.remove(0);

                bufferSTDOUT = null;
                bufferSTDERR = null;
                lastExitCode = 0;
                lastMarkerSTDOUT = null;
                lastMarkerSTDERR = null;

                if (command.commands.length > 0) {
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
                            Debug.logCommand(String.format("[%s+] %s",
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
                } else {
                    runNextCommand(false);
                }
            } else if (!running || closed) {
                // our shell died for unknown reasons or was closed - abort all submissions
                Debug.log(String.format("[%s%%] SHELL_DIED", shell.toUpperCase(Locale.ENGLISH)));
                while (commands.size() > 0) {
                    postCallback(commands.remove(0), OnResult.SHELL_DIED, null, null, null);
                }
                onClosed();
            }

            if (idle) {
                if (running && doCloseWhenIdle) {
                    close();
                } else if (idle && notifyIdle) {
                    synchronized (idleSync) {
                        idleSync.notifyAll();
                    }
                }
            }
        }

        /**
         * Processes a STDOUT/STDERR line containing an end/exitCode marker
         */
        private synchronized void processMarker() {
            if (command.marker.equals(lastMarkerSTDOUT)
                    && (command.marker.equals(lastMarkerSTDERR))) {
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
        private synchronized void processLine(final String line, final Object listener, final boolean isSTDERR) {
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
        private synchronized void addBuffer(String line, boolean isSTDERR) {
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
        private void startCallback() {
            synchronized (callbackSync) {
                callbacks++;
            }
        }

        /**
         * Schedule a callback to run on the appropriate thread
         *
         * @return if callback has already completed
         */
        private boolean postCallback(final Command fCommand, final int fExitCode,
                                     final List<String> fSTDOUT, final List<String> fSTDERR,
                                     final InputStream inputStream) {
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
        private void endCallback() {
            synchronized (callbackSync) {
                callbacks--;
                if (callbacks == 0) {
                    callbackSync.notifyAll();
                }
            }
        }

        /**
         * Internal call that launches the shell, starts gobbling, and starts
         * executing commands. See {@link Shell.Interactive}
         *
         * @return Opened successfully ?
         */
        private synchronized boolean open() {
            Debug.log(String.format("[%s%%] START", shell.toUpperCase(Locale.ENGLISH)));

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

                OnStreamClosedListener onStreamClosedListener = new OnStreamClosedListener() {
                    @Override
                    public void onStreamClosed() {
                        if (shellDiesOnSTDOUTERRClose || !isRunning()) {
                            synchronized (Interactive.this) {
                                // our shell died for unknown reasons - abort all submissions
                                if (command != null) {
                                    if (command.markerInputStream != null) {
                                        command.markerInputStream.setEOF();
                                    }
                                    postCallback(command, OnResult.SHELL_DIED, null, null, null);
                                    command = null;
                                }
                                closed = true;
                                opening = false;
                                runNextCommand();
                            }
                        }
                    }
                };

                STDIN = new DataOutputStream(process.getOutputStream());
                STDOUT = new StreamGobbler(shell.toUpperCase(Locale.ENGLISH) + "-",
                        process.getInputStream(), new OnLineListener() {
                    @Override
                    public void onLine(String line) {
                        Command cmd = command;
                        if ((cmd != null) && (cmd.onCommandInputStreamListener != null)) {
                            // we need to suspend the normal input reader
                            if (line.equals("inputstream")) {
                                STDOUT.suspendGobbling();
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
                    @Override
                    public void onLine(String line) {
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
         * Currently unused
         */
        private void onClosed() {
            // callbacks may still be scheduled/running at this point
        }

        /**
         * Close shell and clean up all resources. Call this when you are done
         * with the shell. If the shell is not idle (all commands completed) you
         * should not call this method from the main UI thread because it may
         * block for a long time. This method will intentionally crash your app
         * (if in debug mode) if you try to do this anyway.
         */
        public void close() {
            boolean _idle = isIdle(); // idle must be checked synchronized

            synchronized (this) {
                if (!running)
                    return;
                running = false;
                closed = true;
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

                // if we're called from the OnShellOpenResult callback wrapper, we would deadlock
                if ((Thread.currentThread() != STDOUT) && (Thread.currentThread() != STDERR)) {
                    STDOUT.join();
                    STDERR.join();
                }
                stopWatchdog();
                process.destroy();
            } catch (IOException e) {
                // various unforseen IO errors may still occur
            } catch (InterruptedException e) {
                // this should really be re-thrown
            }

            Debug.log(String.format("[%s%%] END", shell.toUpperCase(Locale.ENGLISH)));

            onClosed();
        }

        public synchronized void closeWhenIdle() {
            doCloseWhenIdle = true;
            if (idle) {
                close();
            }
        }

        /**
         * Try to clean up as much as possible from a shell that's gotten itself
         * wedged. Hopefully the StreamGobblers will croak on their own when the
         * other side of the pipe is closed.
         */
        public synchronized void kill() {
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

            onClosed();
        }

        /**
         * Is our shell currently being opened ?
         *
         * @return Shell opening ?
         */
        public boolean isOpening() {
            return isRunning() && opening;
        }

        /**
         * Is our shell still running ?
         *
         * @return Shell running ?
         */
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
        public synchronized boolean isIdle() {
            if (!isRunning()) {
                idle = true;
                opening = false;
                synchronized (idleSync) {
                    idleSync.notifyAll();
                }
            }
            return idle;
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
         * See {@link Shell.Interactive} for further details on threading and
         * handlers
         * </p>
         *
         * @return True if wait complete, false if wait interrupted
         */
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
            }

            return true;
        }

        /**
         * Are we using a Handler to post callbacks ?
         *
         * @return Handler used ?
         */
        public boolean hasHandler() {
            return (handler != null);
        }
    }
}
