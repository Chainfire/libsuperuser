# libsuperuser

[![ci][1]][2]

Example code for "How-To SU"

For some outdated background details, see:

[http://su.chainfire.eu/](http://su.chainfire.eu/)

Even though its outdated with regards to usage of this library,
if you're unfamiliar with writing code for root usage, it is not
a bad idea to read it.

## License

Copyright &copy; 2012-2019 Jorrit *Chainfire* Jongma

This code is released under the [Apache License version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Deprecated

This library is not under active development right now, as I've mostly
moved away from the Android world. While I believe it still works great,
if it breaks due to changes on new Android versions or root solutions,
fixes may be slow to appear.

If you're writing a new app, you might consider using
[TopJohnWu's libsu](https://github.com/topjohnwu/libsu) instead. Barring
some edge-cases (that I personally seem to be the biggest user of) the
capabilities should be similar, but it's likely to be better maintained.

## v1.1.0 update

It is now 2019, 7 years since the initial release of
*libsuperuser*, and I have *finally* gotten around to releasing v1.1.0,
and writing an updated how-to. See, I don't need reminding every 6
months.

This update brings support for commands returning an `InputStream` for
STDOUT, as well as adding per-line and buffered STDERR support to
various methods.

As `Shell.Interactive` can be a bit tricky to use and understand
callback and threading wise, especially when used from a background
thread, the `Shell.Threaded` subclass has been added. This class
maintains its own dedicated background thread, upon which all the
callbacks are executed.

`Shell.Interactive` (and `Shell.Threaded`) have gained synchronous
methods, that may be easier to handle than the asynchronous ones, when
used from a background thread. Obviously one cannot use them from the
main UI thread, as this would block the UI.

Last but not least, `Shell.Pool` has been added, which maintains a
pool of `Shell.Threaded` instances for your app to use; created, cached,
and closed on-demand. For new users, `Shell.Pool` is the place to start.

If you're looking at the source of the library, `Shell.java` has
become way too large and would look better broken up. This is
intentionally not done to maintain better backward compatibility
with old code, of which there is quite a bit.

## Upgrading from v1.0.0 to v1.1.0

No functionality has been removed, but some of the method signatures
have subtly changed, and a lot of methods have been deprecated
(though they will not be removed). The compiler will certainly tell
you about these. Some interface have been renamed, and some methods
were added to existing interfaces. All `Exception` based classes have
moved to inner classes of `Shell`.

`Shell.run(...)`, and all `Shell.SH.xxx` and `Shell.SU.xxx` methods
automatically redirect to their `Shell.Pool.xxx` counterparts. This
is a free speed-up for code using these methods. The redirection
can be turned off by calling `Shell.setRedirectDeprecated(false)`
from something like `Application::onCreate()`.

While most code should run the same without issue, you should
definitely double check, especially for complicated scripts or
commands that set specific environment variables.

`Shell.Interactive` should work exactly as it always has, but
since some threading-related code has changed internally, it is
always wise to check if everything still works as expected.

There is no need to migrate existing `Shell.Interactive` code to
`Shell.Threaded`, unless you want to use the functionality
provided by `Shell.Pool`. Be sure to read about the usage difference
between them below.

Last but not least, `minSdkVersion` was updated from 4 to 5, so
we're losing compatibility with Android 1.6 Donut users, sorry.

## Example project

The example project is very old, and does not follow current best
practises. While `PooledActivity` has been added demonstrating
some calls using `Shell.Threaded` and `Shell.Pool`, they aren't
particularly good. The old code demonstrating both legacy and
interactive modes remains present. Use the mode button at the bottom
to switch between activities.

## Basics

This page is not intended as a full reference, just to get you
started off. There are many methods and classes in the library
not explained here. For more advanced usages, consult the source
code - over 1/3rd of the lines belong to comments.

Some of the below may seem out-of-order, you might want to read
this entire section twice.

#### Blocking, threads, and ShellOnMainThreadException

Running subprocesses is expensive and timings cannot be predicted.
For something like running "su" even more so, as it can launch
a dialog waiting for user interaction. Many methods in this library
may be *blocking* (taking unpredictable time to return). When you
attempt to call any of these methods from the main UI thread, the
library will throw a `Shell.ShellOnMainThreadException` at you, if
your app is compiled in debug mode. (Note that this behavior can
be disabled through the `Debug.setSanityChecksEnabled(false)` call).

Methods that may throw this exception include any of the `run(...)`,
`waitFor...()`, and `close...()` methods, with the exception of
`closeWhenIdle()`.

The `Shell.Builder`, `Shell.Interactive` and `Shell.Threaded` classes
provide `addCommand(...)` methods, which run asynchronously and provide
completion callbacks. `addCommand(...)` can safely be called from
the main UI thread.

`Shell.Interactive` (and its `Shell.Threaded` subclass) is a class
wrapping a running instance of a shell (such as "sh" or "su"),
providing methods to run commands in that shell and return the output
of each individual command and its exit code. As opening a shell
itself can be very expensive (especially so with "su"), it is
preferred to use few interactive shells to run many commands rather
than executing a single shell for each individual command.

`Shell.Interactive` (and its `Shell.Threaded` subclass) uses two
background threads to continuously gobble the input from STDOUT and
STDERR. This is an (unfortunate) requirement to prevent the underlying
shell from possibly deadlocking if it produces large amounts of output.

When an instance of `Shell.Interactive` is created, it determines if
the calling thread has an Android `Looper` attached, if it does, it
creates an Android `Handler`, to which all callbacks (such as the
interfaces passed to `addCommand(...)`) are passed. The callbacks
are then executed on the original calling thread. If a `Looper` is
not available, callbacks are usually executed on the gobbler threads
(which increases the risk of deadlocks, and should be avoided), but
may also be executed on the calling thread (which can cause deadlocks
in your own threading code).

(Didn't make sense? Don't worry about it, and just follow the
advice and examples below)

#### `Shell.Interactive` vs `Shell.Threaded`

`Shell.Interactive`'s threading/callback model *can* be fine when it's
used from the main UI thread. As the main UI thread most certainly has
a `Looper`, there is no problem creating a `Handler`, and the callbacks
are run directly on the main UI thread. While this does allow you to
directly manipulate UI elements from the callbacks, it also causes
jank if your callbacks take too long to execute.

However, when `Shell.Interactive` is used from a background thread,
unless you manually create and manage a special secondary thread for
it (a `HandlerThread`), callbacks run on the gobbler threads, which is
potentially bad.

The `Shell.Threaded` subclass specifically creates and manages this
secondary `HandlerThread` for you, and guarantees all callbacks are
executed on that thread. This prevents most deadlock situations from
happening, and is consistent in its behavior across the board.

The drawback there is that you cannot directly manipulate UI elements
from the callbacks passed to `addCommand(...)` (or any other methods),
but that is probably not what you end up wanting to do in any
real-world app anyway. When the need arises, you can use something
like `Activity::runOnUiThread(...)` to call code that adjusts the UI.

Additionally, `Shell.Threaded` is easier to setup and supports pooling
via `Shell.Pool` (explained further below). The choice which to use
should be easy at this point, unless you have some very specific needs.

If you are porting from `Shell.Interactive` to `Shell.Threaded`, please
note that the behavior of the `close()` method is different between
the two. In `Shell.Interactive` it redirects to `closeImmediately()`,
which waits for all commands to complete and then closes the shell.
In `Shell.Threaded` it returns the shell to the pool if it is
part of one, and otherwise redirects to `closeWhenIdle()`, which
schedules the actual close when all commands have completed, but
returns immediately. This discrepancy is unfortunate but required
to maintain both good backwards compatibility and support pooling
with try-with-resources.

#### Common methods

Examples follow further below, which make use of pooling. But before
pooling can be explained, the common methods you will use with
different classes need a quick walk-through.

#### Common methods: `addCommand(...)`

The `Shell.Builder` (used to manually construct `Shell.Interactive`
and `Shell.Threaded` instances), `Shell.Interactive` and
`Shell.Threaded` classes provide `addCommand(...)` methods. These
run asynchronously and are safe to call from the main UI thread: they
return before the commands complete, and an optionally provided
callback is executed when the command does complete:

- `addCommand(Object commands)`

- `addCommand(Object commands, int code, OnResult onResultListener)`

`commands` accepts a `String`, a `List<String>`, or a `String[]`.

`onResultListener` is one of:

- `OnCommandResultListener2`, which buffers STDOUT and STDERR and
returns them to the callback all in one go

- `OnCommandLineListener`, which is unbuffered and is called once
for each line read from STDOUT or STDERR

- `OnCommandInputStreamListener`, which is called with an
`InputStream` you can use to read raw data from the shell. You
should continue reading the `InputStream` until *-1* is returned
(*not 0* as is sometimes done), or further commands on this shell
will not execute. You can call `InputStream::close()` to do this
for you. Additionally, if the shell is closed during reading, then
(and only then) an `IOException` will be thrown.

All of these provide an `onCommandResult` method that is called
with the `code` you passed in, and the exit code of the (last) of the
commands passed in. Note that the exit code will be < 0 if an error
occurs, such as the shell being closed.

The `addCommand(...)` calls will *not* be further explained in this
document, consult the example project (`InteractiveActivity.java`)
and the library source for further details.

#### Common methods: `run(...)`

The `Shell.Interactive`, `Shell.Threaded`, and `Shell.PoolWrapper`
classes provide `run(...)` methods. These run synchronously and are
*not* safe to call from the main UI thread: they return when the
command is completed:

- `int run(Object commands)`

- `int run(Object commands, List<String> STDOUT, List<String> STDERR, boolean clear)`

- `int run(Object commands, OnSyncCommandLineListener onSyncCommandLineListener)`

- `int run(Object commands, OnSyncCommandInputStreamListener onSyncCommandInputStreamListener)`

As before, `commands` accepts a `String`, a `List<String>`, or a `String[]`.

It should be obvious that these are simply the synchronous counterparts
of the asynchronous `addCommand(...)` methods.

Instead of calling a callback interface with the exit code, it is
returned directly, and instead of returning a negative exit code on
error, `Shell.ShellDiedException` is thrown.

#### Pooling

The `Shell.Pool` class provides shell pooling. It will create new
shell instances on-demand, and keep a set number of them around for
reuse later (4 by default for "su" instances, 1 for non-"su" instances).

`Shell.Pool.SH` and `Shell.Pool.SU` are pre-created instances of
`Shell.PoolWrapper` for "sh" and "su", providing `get()` and the
earlier mentions `run(...)` methods for the pool.

The `get()` method can be used to retrieve a `Shell.Threaded` instance
from the pool, which you should later return to the pool by calling
it's `close()` method.

The `run(...)` methods, instead of operating on a specific
`Shell.Threaded` instance you manage, retrieve an instance from the
pool, proxies the call to that instance's `run(...)` method, and
then immediately returns the instance to the pool.

Sound complex? Maybe, but it all comes together so you can sprinkle
`Shell.Pool.SU.run(...)` calls throughout as many threads as you wish
(barring of course the main UI thread), running simultaneously or not,
with instances being created, reused, and closed automatically. All of
this without you ever having to worry about managing the instances,
and only having to catch a single `Shell.ShellDiedException`.

#### Examples

It is assumed all the code following is run from a background thread,
such as `Thread`, `AsyncTask`, or `(Job)IntentService`.

Running some basic commands:

```
try {
    List<String> STDOUT = new ArrayList<String>();
    List<String> STDERR = new ArrayList<String>();
    int exitCode;

    exitCode = Shell.Pool.SU.run("echo nobody will ever see this");
    // we have only an exit code

    exitCode = Shell.Pool.SU.run("ls -l /", STDOUT, STDERR, true);
    // exit code, and STDOUT/STDERR output

    exitCode = Shell.Pool.SU.run("cat /init.rc", new Shell.OnSyncCommandInputStreamListener() {
        @Override
        public void onInputStream(InputStream inputStream) {
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = inputStream.read(buf)) >= 0) {
                    // do something with buf

                    // if we decide to abort before r == -1, call inputStream.close()
                }
            } catch (IOException e) {
                // shell died during read
            }
        }

        @Override
        public void onSTDERR(String line) {
            // hey, some output on STDERR!
        }
    });

    Shell.Pool.SU.run("logcat -d", new Shell.OnSyncCommandLineListener() {
        @Override
        public void onSTDOUT(String line) {
            // hey, some output on STDOUT!
        }

        @Override
        public void onSTDERR(String line) {
            // hey, some output on STDERR!
        }
    });

} catch (Shell.ShellDiedException e) {
    // su isn't present, access was denied, or the shell terminated while 'run'ing
}
```

When running multiple commands in quick succession, it is slightly
cheaper to `get()` an instance and `close()` it when done, and using
the returned instance. But keep in mind if there is a longer period
between your calls, and another thread wants to call su, the shell you
have not `close()`'d yet cannot be reused by that thread:

```
try {

    // get an instance from the pool
    Shell.Threaded shell = Shell.Pool.SU.get();
    try {

        // this is very useful
        for (int i = 0; i < 100; i++) {
            shell.run("echo nobody will ever see this");
        }

    } finally {
        // return the instance to the pool
        shell.close();
    }

} catch (Shell.ShellDiedException e) {
    // su isn't present, access was denied, or the shell terminated while 'run'ing
}
```

If you're targeting API >= 19 and Java 1.8, you can use
try-with-resources with `Shell.Threaded::ac()`, which casts the
instance to a `Shell.ThreadedAutoCloseable`:

```
try {

    // get an instance from the pool, automatically returning it at the end of the try block
    try (Shell.ThreadedAutoCloseable shell = Shell.Pool.SU.get().ac()) {

        // this is very useful
        for (int i = 0; i < 100; i++) {
            shell.run("echo nobody will ever see this");
        }

    }

} catch (Shell.ShellDiedException e) {
    // su isn't present, access was denied, or the shell terminated while 'run'ing
}
```

## libRootJava

For more advanced usages of root, such as running Java/Kotlin code as
root directly, please see my [libRootJava](https://github.com/Chainfire/librootjava)
library.

## Annotations

Nullity and thread annotations have recently been added.

Please note that *all* methods that *may* be problematic on the UI
thread have been marked as `@WorkerThread`. Some of these methods
can be called from the UI thread without issue in specific conditions.
If so, those conditions should be noted in the method's javadoc.

## Gradle

Gradle:

```
dependencies {
    implementation 'eu.chainfire:libsuperuser:1.1.0.+'
}
```


Old version, pre-Threaded/Pools:

```
dependencies {
    implementation 'eu.chainfire:libsuperuser:1.0.0.+'
}
```

[1]: https://github.com/Chainfire/libsuperuser/workflows/ci/badge.svg
[2]: https://github.com/Chainfire/libsuperuser/actions
