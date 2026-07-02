package io.github.hakjuoh.protege_mcp.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared plumbing for driving a coding-agent CLI ({@code claude} / {@code codex}) as a subprocess:
 * resolving the executable's absolute path (a Finder/Dock-launched Protégé has a minimal {@code PATH}),
 * probing its version, and spawning it with its stdout pumped line-by-line off the EDT.
 */
public final class CliSupport {

    private CliSupport() {
    }

    /**
     * Resolve the absolute path of executable {@code name}. Tries (1) an explicit {@code override}
     * (a full path, or a directory to search), (2) {@code $PATH}, (3) well-known install dirs that a
     * GUI-launched process often misses. Returns {@code null} if not found.
     */
    public static String resolveExecutable(String name, String override) {
        if (override != null && !override.isBlank()) {
            File direct = new File(override.trim());
            if (direct.isFile() && direct.canExecute()) {
                return direct.getAbsolutePath();
            }
            File inDir = new File(direct, name);
            if (inDir.isFile() && inDir.canExecute()) {
                return inDir.getAbsolutePath();
            }
        }
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            Collections.addAll(dirs, path.split(File.pathSeparator));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            dirs.add(home + "/.local/bin");
            dirs.add(home + "/.npm-global/bin");
            dirs.add(home + "/.bun/bin");
            dirs.add(home + "/bin");
        }
        dirs.add("/opt/homebrew/bin");
        dirs.add("/usr/local/bin");
        dirs.add("/usr/bin");
        for (String d : dirs) {
            if (d == null || d.isBlank()) {
                continue;
            }
            File f = new File(d, name);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** Run {@code exe --version} and return its first output line, or {@code null} on failure. */
    public static String probeVersion(String exe) {
        if (exe == null) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "--version");
            pb.redirectErrorStream(true);
            ensureHome(pb.environment());
            ensurePath(pb.environment());
            Process p = pb.start();
            String line;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return line;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Spawn {@code command}, returning immediately with a {@link ChatProcess} handle. The child's
     * stdin is closed (so a prompt-on-stdin CLI sees EOF), its stdout is read line-by-line on a daemon
     * worker (each line passed to {@code lineHandler}), and its stderr is drained into a buffer. When
     * the process exits, {@code completionHandler} is called with the exit code and captured stderr.
     */
    public static ChatProcess spawn(List<String> command, Map<String, String> extraEnv, File workingDir,
            Consumer<String> lineHandler, BiConsumer<Integer, String> completionHandler) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(loginShellWrap(command));
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        Map<String, String> env = pb.environment();
        ensureHome(env);
        ensurePath(env);
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
        pb.redirectErrorStream(false);
        Process p = pb.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // stdin already closed
        }

        StringBuilder errBuf = new StringBuilder();
        Thread errThread = new Thread(() -> drain(p.getErrorStream(), errBuf), "protege-chat-stderr");
        errThread.setDaemon(true);
        errThread.start();

        Thread worker = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        lineHandler.accept(line);
                    } catch (RuntimeException ex) {
                        // a single malformed/unexpected line must not kill the stream
                    }
                }
            } catch (IOException ignored) {
                // stream closed (e.g. process killed)
            } finally {
                int exit = -1;
                try {
                    exit = p.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                try {
                    errThread.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                completionHandler.accept(exit, errBuf.toString());
            }
        }, "protege-chat-agent");
        worker.setDaemon(true);
        worker.start();
        return new ChatProcess(p, worker);
    }

    /**
     * Run {@code command} through the user's interactive login shell so it inherits the same
     * environment their terminal has. This is load-bearing on macOS: a Finder/Dock-launched Protégé
     * never sources the user's shell profile, so an agent CLI authenticated via a profile-exported
     * variable (e.g. {@code export ANTHROPIC_API_KEY=…} in {@code ~/.profile}) — or whose login flow
     * the profile sets up — reports "Not logged in" when spawned directly. {@code $SHELL -lc 'exec …'}
     * sources the login profile and then {@code exec}s the CLI in-place (same PID, so cancel/kill still
     * targets the CLI). Each argv element is single-quote-escaped so the JSON config and free-text
     * prompt cannot break the command line. On Windows (no POSIX login shell) the command runs directly.
     */
    static List<String> loginShellWrap(List<String> command) {
        return loginShellWrap(command, System.getProperty("os.name", ""), System.getenv("SHELL"));
    }

    /**
     * OS/env-parametrized core of {@link #loginShellWrap(List)}, split out so the platform branch,
     * shell resolution and argv quoting can be exercised headless without touching the real
     * {@code os.name} / {@code $SHELL}. On Windows the command is returned unwrapped.
     */
    static List<String> loginShellWrap(List<String> command, String osName, String shellEnv) {
        if (osName != null && osName.toLowerCase().contains("win")) {
            return command;
        }
        String shell = resolveLoginShell(shellEnv);
        StringBuilder script = new StringBuilder("exec");
        for (String arg : command) {
            script.append(' ').append(shellQuote(arg));
        }
        return List.of(shell, "-lc", script.toString());
    }

    /**
     * Resolve the POSIX login shell to wrap with: {@code shellEnv} (the caller's {@code $SHELL}) if it
     * points at an executable, else {@code /bin/bash}, else {@code /bin/sh}.
     */
    static String resolveLoginShell(String shellEnv) {
        String shell = shellEnv;
        if (shell == null || shell.isBlank() || !new File(shell).canExecute()) {
            shell = "/bin/bash";
        }
        if (!new File(shell).canExecute()) {
            shell = "/bin/sh";
        }
        return shell;
    }

    /** POSIX single-quote escaping: wrap in {@code '…'}, turning each embedded quote into {@code '\''}. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void drain(InputStream in, StringBuilder sink) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sink) {
                    if (sink.length() < 8000) {
                        sink.append(line).append('\n');
                    }
                }
            }
        } catch (IOException ignored) {
            // stream closed
        }
    }

    /**
     * Write {@code content} to a fresh temp file created owner-only (POSIX {@code rw-------} where the
     * filesystem supports it, best-effort owner-restricted elsewhere), and return it. Used to keep a
     * secret — e.g. an MCP bearer token — OFF the process command line: the caller passes the file PATH
     * as an argument instead of the secret itself, so it is not exposed to {@code ps} / other local
     * users. The file is created with the restricted permissions <em>before</em> any content is written.
     * The caller owns its lifecycle (delete it when the child process exits).
     */
    public static File writeOwnerOnlyTempFile(String prefix, String suffix, String content)
            throws IOException {
        Path path;
        try {
            path = Files.createTempFile(prefix, suffix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            // Non-POSIX filesystem (e.g. Windows): create, then best-effort restrict to the owner.
            File f = File.createTempFile(prefix, suffix);
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
            path = f.toPath();
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path.toFile();
    }

    /** A neutral working directory so a CLI does not auto-discover a project's config (CLAUDE.md, etc.). */
    public static File neutralWorkingDir() {
        File dir = new File(System.getProperty("java.io.tmpdir", "."), "protege-mcp-chat");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir.isDirectory() ? dir : new File(System.getProperty("user.home", "."));
    }

    /** A concise one-line failure message from a non-zero exit + captured stderr (last bytes). */
    public static String describeFailure(String name, int exit, String stderr) {
        String tail = stderr == null ? "" : stderr.trim();
        if (tail.length() > 600) {
            tail = "…" + tail.substring(tail.length() - 600);
        }
        String base = name + " exited with code " + exit;
        return tail.isEmpty() ? base + "." : base + ": " + tail;
    }

    private static void ensureHome(Map<String, String> env) {
        if (!env.containsKey("HOME")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                env.put("HOME", home);
            }
        }
    }

    /**
     * Guarantee the child sees a usable {@code PATH}. A Finder/Dock-launched Protégé inherits a stripped
     * {@code PATH} (often without {@code /usr/bin}), and the agent CLIs shell out to helpers by bare name
     * — notably {@code claude} runs {@code security find-generic-password} to read its keychain login, so
     * a missing {@code /usr/bin} makes it report "Not logged in" with no prompt. Keep any inherited entries
     * (the user's tool choices) and append the standard system + common install dirs that must be present.
     */
    private static void ensurePath(Map<String, String> env) {
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        String existing = env.get("PATH");
        if (existing != null) {
            for (String d : existing.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(d);
                }
            }
        }
        for (String d : new String[] {"/usr/bin", "/bin", "/usr/sbin", "/sbin",
                "/usr/local/bin", "/opt/homebrew/bin"}) {
            dirs.add(d);
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            dirs.add(home + "/.local/bin");
        }
        env.put("PATH", String.join(File.pathSeparator, dirs));
    }
}
