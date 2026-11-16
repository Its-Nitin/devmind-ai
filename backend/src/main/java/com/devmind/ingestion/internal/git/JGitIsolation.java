package com.devmind.ingestion.internal.git;

import java.io.File;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;

/**
 * Cuts JGit off from the host's Git setup, so only the repository's own config applies:
 *
 * <ul>
 *   <li>no system, user (~/.gitconfig) or JGit config. A user config could rewrite URLs with {@code
 *       url.<base>.insteadOf} and so bypass the host allow-list (SEC-3), and finding the system config makes JGit run
 *       the {@code git} executable (SEC-4);
 *   <li>no {@code GIT_*} environment variables, for the same reasons ({@code GIT_CONFIG_*}, {@code GIT_DIR}, ...).
 * </ul>
 *
 * <p>JGit's {@link SystemReader} is JVM-wide, so this is installed once, before any repository is opened.
 */
final class JGitIsolation {

    private JGitIsolation() {}

    /** Installs the isolation if it isn't already; safe to call repeatedly and concurrently. */
    static synchronized void install() {
        SystemReader current = SystemReader.getInstance();
        if (!(current instanceof IsolatedSystemReader)) {
            SystemReader.setInstance(new IsolatedSystemReader(current));
        }
    }

    private static final class IsolatedSystemReader extends SystemReader.Delegate {

        IsolatedSystemReader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public @Nullable String getenv(String variable) {
            return variable.startsWith("GIT_") ? null : super.getenv(variable);
        }

        @Override
        public FileBasedConfig openUserConfig(@Nullable Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(@Nullable Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openJGitConfig(@Nullable Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }
    }

    /** A config with no backing file: loads nothing and saves nothing. */
    private static final class EmptyConfig extends FileBasedConfig {

        @SuppressWarnings("NullAway") // JGit's own "no file" config passes a null location the same way
        EmptyConfig(@Nullable Config parent, FS fs) {
            super(parent, (File) null, fs);
        }

        @Override
        public void load() {
            // nothing to load
        }

        @Override
        public void save() {
            // nothing to save: JGit's own caches (e.g. filesystem timestamp resolution) stay in memory
        }

        @Override
        public boolean isOutdated() {
            return false;
        }
    }
}
