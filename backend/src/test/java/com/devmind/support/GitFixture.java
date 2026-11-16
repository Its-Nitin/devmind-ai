package com.devmind.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Builds Git repositories for tests, writing trees and commits straight into the object database: no {@code git} CLI,
 * no network, the same SHAs on every run, and any entry mode (symlinks, submodules) on any OS, without needing the
 * filesystem to support them. Nothing binary is checked in (M2 step 3).
 */
public final class GitFixture implements AutoCloseable {

    private static final PersonIdent AUTHOR =
            new PersonIdent("Fixture", "fixture@example.com", Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private final Repository repository;
    private int commits;

    private GitFixture(Repository repository) {
        this.repository = repository;
    }

    /** Creates a repository with a working tree at {@code workTree} (its Git directory is {@code workTree/.git}). */
    public static GitFixture init(Path workTree) {
        return create(new FileRepositoryBuilder().setWorkTree(workTree.toFile()), false);
    }

    /** Creates a bare repository at {@code gitDirectory}. */
    public static GitFixture initBare(Path gitDirectory) {
        return create(
                new FileRepositoryBuilder().setGitDir(gitDirectory.toFile()).setBare(), true);
    }

    private static GitFixture create(FileRepositoryBuilder builder, boolean bare) {
        try {
            Repository repository = builder.build();
            repository.create(bare);
            return new GitFixture(repository);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Returns an empty tree to fill. */
    public static Tree tree() {
        return new Tree();
    }

    public Repository repository() {
        return repository;
    }

    /** Returns the repository's Git directory, e.g. {@code <workTree>/.git}. */
    public Path gitDirectory() {
        return repository.getDirectory().toPath();
    }

    /** Commits exactly {@code tree} on {@code branch}, on top of its current tip if it has one. */
    public RevCommit commit(String branch, Tree tree) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, Tree.Entry> entry : tree.entries.entrySet()) {
                DirCacheEntry file = new DirCacheEntry(entry.getKey());
                file.setFileMode(entry.getValue().mode());
                file.setObjectId(entry.getValue().object(inserter));
                builder.add(file);
            }
            builder.finish();
            ObjectId treeId = index.writeTree(inserter);
            inserter.flush();
            return commitTree(branch, treeId);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Commits a tree written entry by entry, bypassing the path checks that {@link #commit(String, Tree)} inherits from
     * JGit's index: for trees a crafted repository could contain, e.g. an entry named {@code ..}.
     */
    public RevCommit commitCraftedTree(String branch, String rootEntry, String childEntry, String content) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            TreeFormatter child = new TreeFormatter();
            child.append(
                    childEntry,
                    FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)));
            TreeFormatter root = new TreeFormatter();
            root.append(rootEntry, FileMode.TREE, inserter.insert(child));
            ObjectId treeId = inserter.insert(root);
            inserter.flush();
            return commitTree(branch, treeId);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private RevCommit commitTree(String branch, ObjectId treeId) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter();
                RevWalk walk = new RevWalk(repository)) {
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(AUTHOR);
            commit.setCommitter(AUTHOR);
            commit.setMessage("commit " + ++commits);
            Ref tip = repository.exactRef(Constants.R_HEADS + branch);
            if (tip != null) {
                commit.setParentId(tip.getObjectId());
            }
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            update(Constants.R_HEADS + branch, id);
            return walk.parseCommit(id);
        }
    }

    /** Points {@code refs/tags/<name>} straight at {@code target}. */
    public void lightweightTag(String name, ObjectId target) {
        update(Constants.R_TAGS + name, target);
    }

    /** Creates an annotated tag object for {@code commit} and points {@code refs/tags/<name>} at it. */
    public ObjectId annotatedTag(String name, ObjectId commit) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            TagBuilder tag = new TagBuilder();
            tag.setObjectId(commit, Constants.OBJ_COMMIT);
            tag.setTag(name);
            tag.setTagger(AUTHOR);
            tag.setMessage("tag " + name);
            ObjectId id = inserter.insert(tag);
            inserter.flush();
            update(Constants.R_TAGS + name, id);
            return id;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void update(String refName, ObjectId target) {
        try {
            RefUpdate update = repository.updateRef(refName);
            update.setNewObjectId(target);
            RefUpdate.Result result = update.forceUpdate();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED) {
                throw new IllegalStateException("couldn't update " + refName + ": " + result);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void close() {
        repository.close();
    }

    /** The entries of one commit's tree, by path. */
    public static final class Tree {

        private final Map<String, Entry> entries = new TreeMap<>();

        private Tree() {}

        public Tree file(String path, String content) {
            return file(path, content.getBytes(StandardCharsets.UTF_8));
        }

        public Tree file(String path, byte[] content) {
            entries.put(path, new Entry(FileMode.REGULAR_FILE, content, null));
            return this;
        }

        public Tree executable(String path, String content) {
            entries.put(path, new Entry(FileMode.EXECUTABLE_FILE, content.getBytes(StandardCharsets.UTF_8), null));
            return this;
        }

        /** A symlink entry; its blob is the target path, as Git stores it. */
        public Tree symlink(String path, String target) {
            entries.put(path, new Entry(FileMode.SYMLINK, target.getBytes(StandardCharsets.UTF_8), null));
            return this;
        }

        /** A submodule (gitlink) entry pointing at a commit of another repository. */
        public Tree submodule(String path, String commitSha) {
            entries.put(path, new Entry(FileMode.GITLINK, new byte[0], ObjectId.fromString(commitSha)));
            return this;
        }

        /** A class, not a record: records shouldn't hold arrays (Error Prone ArrayRecordComponent). */
        private static final class Entry {

            private final FileMode mode;
            private final byte[] content;
            private final @Nullable ObjectId gitlink;

            Entry(FileMode mode, byte[] content, @Nullable ObjectId gitlink) {
                this.mode = mode;
                this.content = content.clone();
                this.gitlink = gitlink;
            }

            FileMode mode() {
                return mode;
            }

            ObjectId object(ObjectInserter inserter) throws IOException {
                return gitlink != null ? gitlink : inserter.insert(Constants.OBJ_BLOB, content);
            }
        }
    }
}
