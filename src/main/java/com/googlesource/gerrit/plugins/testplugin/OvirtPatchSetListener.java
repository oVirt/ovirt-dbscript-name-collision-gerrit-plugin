    // Copyright (C) 2015 The Android Open Source Project
    //
    // Licensed under the Apache License, Version 2.0 (the "License");
    // you may not use this file except in compliance with the License.
    // You may obtain a copy of the License at
    //
    // http://www.apache.org/licenses/LICENSE-2.0
    //
    // Unless required by applicable law or agreed to in writing, software
    // distributed under the License is distributed on an "AS IS" BASIS,
    // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    // See the License for the specific language governing permissions and
    // limitations under the License.

    package com.googlesource.gerrit.plugins.testplugin;

    import com.google.gerrit.common.EventListener;
    import com.google.gerrit.extensions.annotations.Listen;
    import com.google.gerrit.reviewdb.client.Branch;
    import com.google.gerrit.reviewdb.client.PatchSet;
    import com.google.gerrit.reviewdb.client.Project;
    import com.google.gerrit.reviewdb.server.ReviewDb;
    import com.google.gerrit.server.events.CommitReceivedEvent;
    import com.google.gerrit.server.events.DraftPublishedEvent;
    import com.google.gerrit.server.events.Event;
    import com.google.gerrit.server.git.CodeReviewCommit;
    import com.google.gerrit.server.git.GitRepositoryManager;
    import com.google.gerrit.server.git.validators.CommitValidationException;
    import com.google.gerrit.server.git.validators.CommitValidationListener;
    import com.google.gerrit.server.git.validators.CommitValidationMessage;
    import com.google.gerrit.server.git.validators.MergeValidationException;
    import com.google.gerrit.server.git.validators.MergeValidationListener;
    import com.google.gerrit.server.project.ProjectState;
    import com.google.gwtorm.server.OrmException;
    import com.google.inject.Inject;
    import com.google.inject.Provider;
    import com.google.inject.Singleton;
    import org.eclipse.jgit.api.Git;
    import org.eclipse.jgit.api.errors.GitAPIException;
    import org.eclipse.jgit.diff.DiffEntry;
    import org.eclipse.jgit.lib.Constants;
    import org.eclipse.jgit.lib.ObjectReader;
    import org.eclipse.jgit.lib.Repository;
    import org.eclipse.jgit.revwalk.RevTree;
    import org.eclipse.jgit.revwalk.RevWalk;
    import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
    import org.eclipse.jgit.treewalk.CanonicalTreeParser;
    import org.eclipse.jgit.treewalk.TreeWalk;
    import org.eclipse.jgit.treewalk.filter.TreeFilter;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    import java.io.IOException;
    import java.nio.file.Paths;
    import java.util.Collections;
    import java.util.HashSet;
    import java.util.List;
    import java.util.Optional;
    import java.util.Set;
    import java.util.function.Function;
    import java.util.function.Supplier;
    import java.util.regex.Matcher;
    import java.util.regex.Pattern;
    import java.util.stream.Collectors;

    @Listen
    @Singleton
    public class OvirtPatchSetListener implements CommitValidationListener, MergeValidationListener, EventListener {
        private static final String FOLDER_PREFIX = "packaging/dbscripts/upgrade";
        // e.g 04_00_0100_description.(sql|sh)
        private static final Pattern FILE_PATTERN = Pattern.compile("(\\d{2}_\\d{2}_\\d{4})_\\w+.s.*"); // sql or sh
        private static final Logger log = LoggerFactory.getLogger(OvirtPatchSetListener.class);

        private final GitRepositoryManager manager;
        private Provider<ReviewDb> dbProvider;

        @Inject
        public OvirtPatchSetListener(final GitRepositoryManager manager, final Provider<ReviewDb> dbProvider) {
            log.info("Loading Ovirt dbscript name-collision detection plugin");
            this.manager = manager;
            this.dbProvider = dbProvider;
        }

        @Override
        public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event) throws CommitValidationException {

            //TODO is draft then ignore

            Set<String> addedFiles = getAddedDbscripts(event.getProjectNameKey(), event.commit.getTree());
            Set<String> existingFiles = getExistingDbscripts(event.getProjectNameKey());

            Optional<Matcher> collision = addedFiles.stream()
                    .map(file -> FILE_PATTERN.matcher(file))
                    .filter(matcher -> matcher.matches() && matcher.groupCount() > 0)
                    .filter(matcher -> existingFiles.stream().anyMatch(file -> file.startsWith(matcher.group(1))))
                    .findFirst();
            if (collision.isPresent()) {
                throw new CommitValidationException("The file " + collision.get().group() + " index collides with existing file");
            }
            return Collections.emptyList();
        }

        private Set<String> getAddedDbscripts(Project.NameKey projectName,RevTree targetRevTree) {
            try (Repository repository = repoFromProjectKey(projectName, manager).get();
                 ObjectReader reader = repository.newObjectReader();
                 RevWalk revWalk = new RevWalk(repository)) {

                CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                oldTreeIter.reset(reader, revWalk.parseCommit(repository.resolve(Constants.HEAD).toObjectId()).getTree());
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                newTreeIter.reset(reader, targetRevTree);

                // finally get the list of changed files
                try (Git git = new Git(repository)) {
                    List<DiffEntry> diffs = git.diff()
                            .setNewTree(newTreeIter)
                            .setOldTree(oldTreeIter)
                            .call();
                    return diffs.stream()
                            .filter(entry -> entry.getChangeType() == DiffEntry.ChangeType.ADD)
                            .filter(entry -> entry.getNewPath().startsWith(FOLDER_PREFIX))
                            .map(entry -> Paths.get(entry.getNewPath()).getFileName().toString())
                            .collect(Collectors.toSet());
                }
            } catch (IOException | GitAPIException e) {
                throw new RuntimeException(e);
            }
        }

        private Set<String> getExistingDbscripts(Project.NameKey projectName) {
            return filesOfCommit(
                    repoFromProjectKey(projectName, manager),
                    getRepositoryRevCommitFunction(Constants.HEAD));
        }

        private Function<Repository, RevTree> getRepositoryRevCommitFunction(String commit) {
            return repository -> {
                try (RevWalk revWalk = new RevWalk(repository)) {
                    return revWalk.parseCommit(repository.resolve(commit)).getTree();
                } catch (IOException e) {
                    log.error("Failed to get HEAD commit of repo {} ", repository, e);
                    throw new IllegalArgumentException("Can't get tree for HEAD of repository");
                }
            };
        }

        public static Set<String> filesOfCommit(Supplier<Repository> repoSupplier, Function<Repository, RevTree> treeSupplier) {
            Set<String> files = new HashSet<>();

            try (Repository repository = repoSupplier.get();
                 TreeWalk treeWalk = new TreeWalk(repository)) {

                treeWalk.addTree(treeSupplier.apply(repository));
                treeWalk.setRecursive(true);
                treeWalk.setFilter(TreeFilter.ANY_DIFF);
                while (treeWalk.next()) {
                    if (treeWalk.getPathString().startsWith(FOLDER_PREFIX)) {
                        files.add(treeWalk.getNameString());
                    }
                }

            } catch (Exception e) {
                log.error("Failed to get files of a commit {} ", treeSupplier, e);
            }
            return files;
        }

        public static Supplier<Repository> repoFromPath(String path) {
            return () -> {
                try {
                    return new FileRepositoryBuilder()
                            .readEnvironment() // scan environment GIT_* variables
                            .findGitDir(Paths.get(path).toFile())
                            .build();
                } catch (IOException e) {
                    log.error("Failed to open a repo from path {}, ", path, e);
                    throw new IllegalArgumentException(e);
                }
            };
        }

        public static Supplier<Repository> repoFromProjectKey(Project.NameKey project, GitRepositoryManager manager) {
            return () -> {
                try {
                    return manager.openRepository(project);
                } catch (IOException e) {
                    log.error("Failed to open the repo {} ", project, e);
                    throw new IllegalArgumentException(e);
                }
            };
        }

        @Override public void onPreMerge(Repository repo,
                CodeReviewCommit commit,
                ProjectState destProject,
                Branch.NameKey destBranch,
                PatchSet.Id patchSetId) throws MergeValidationException {
            throw new NoSuchMethodError("unimplemented");
        }

        @Override public void onEvent(Event event) {
            if (event instanceof DraftPublishedEvent) {
                DraftPublishedEvent publishedEvent = (DraftPublishedEvent) event;

                PatchSet patchSet;
                try {
                    patchSet = dbProvider.get().patchSets().get(PatchSet.Id.fromRef(publishedEvent.patchSet.ref));
                } catch (OrmException e) {
                    throw new RuntimeException("Failed to fetch patchset from db");
                }

                try(Repository repository = repoFromProjectKey(publishedEvent.getProjectNameKey(), manager).get()) {
                    RevTree revTree = getRepositoryRevCommitFunction(patchSet.getRefName()).apply(repository);
                    Set<String> addedFiles = getAddedDbscripts(publishedEvent.getProjectNameKey(), revTree);
                    Set<String> existingFiles = getExistingDbscripts(publishedEvent.getProjectNameKey());

                    Optional<Matcher> collision = addedFiles.stream()
                            .map(file -> FILE_PATTERN.matcher(file))
                            .filter(matcher -> matcher.matches() && matcher.groupCount() > 0)
                            .filter(matcher -> existingFiles.stream()
                                    .anyMatch(file -> file.startsWith(matcher.group(1))))
                            .findFirst();
                    if (collision.isPresent()) {
                        throw new RuntimeException(
                                "The file " + collision.get().group() + " index collides with existing file");
                    }
                }
            }
        }
    }
