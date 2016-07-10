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

import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Listen
@Singleton
public class OvirtPatchSetListener implements CommitValidationListener, MergeValidationListener {
    private static final String FOLDER_PREFIX = "packaging/dbscript/upgrade";
    private static final Pattern FILE_PATTERN = Pattern.compile("(\\d{2}_\\d{2}_\\d{4})_\\w+.sql");
    private static final Logger log = LoggerFactory.getLogger(OvirtPatchSetListener.class);

    private final GitRepositoryManager manager;
    private final ProjectControl.Factory factory;
    private final AccountCache accountCache;
    private final PluginConfig pluginConfig;
    private final PatchListCache patchListCache;
    private final PatchSet patchSet;
    private final Change change;

    @Inject OvirtPatchSetListener(
            final GitRepositoryManager manager,
            final ProjectControl.Factory factory,
            final AccountCache accountCache,
            final PluginConfig pluginConfig,
            final PatchListCache patchListCache,
            @Assisted  final PatchSet patchSet,
            @Assisted  final Change change) {
        this.manager = manager;
        this.factory = factory;
        this.accountCache = accountCache;
        this.pluginConfig = pluginConfig;
        this.patchListCache = patchListCache;
        this.patchSet = patchSet;
        this.change = change;
    }

    public void onEvent(Event event) {
        if (!(event instanceof PatchSetCreatedEvent)) {
            return;
        }


    }


    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event) throws CommitValidationException {

        // get added files which match prefix
//        Set<String> files = getAddedDbscripts(event);
        Set<String> addedFiles = Collections.EMPTY_SET;

    1    try {
            PatchList patchList = patchListCache.get(change, patchSet);
            addedFiles = patchList.getPatches().stream()
                    .filter(e -> e.getChangeType() == Patch.ChangeType.ADDED)
                    .filter(e -> e.getNewName().startsWith(FOLDER_PREFIX + "*.sql"))
                    .map(e -> e.getNewName())
                    .collect(Collectors.toSet());
        } catch (PatchListNotAvailableException e) {
            e.printStackTrace();
        }
        Set<String> existingFiles = getExistingDbscripts(event);

        return addedFiles.stream()
                .map(file -> FILE_PATTERN.matcher(file).group(0))
                .filter(pattern -> existingFiles.stream().anyMatch(file -> file.startsWith(pattern)))
                .map(pattern -> new CommitValidationMessage("A db-script with " + pattern + " exists", false))
                .collect(Collectors.toList());
    }

    private Set<String> getExistingDbscripts(CommitReceivedEvent event) {
        return filesOfCommit(
                repoFromProjectKey(event.getProjectNameKey(), manager),
                event.command.getRefName(),
                FOLDER_PREFIX);
    }

    private Set<String> getAddedDbscripts(CommitReceivedEvent event) {
        return filesOfCommit(
                repoFromProjectKey(event.getProjectNameKey(), manager),
                Constants.HEAD,
                FOLDER_PREFIX);
    }

    public static Set<String> filesOfCommit(Supplier<Repository> repoSupplier, String commit, String pathFilter) {
        Set<String> files = new HashSet<>();
        try (Repository repository = repoSupplier.get();
             TreeWalk treeWalk = new TreeWalk(repository);
             RevWalk revWalk = new RevWalk(repository)) {

            treeWalk.addTree(revWalk.parseTree(repository.resolve(commit)));
            treeWalk.setFilter(PathFilter.create(pathFilter));
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                if (treeWalk.getDepth() != 3) {
                    continue;
                }
                files.add(treeWalk.getNameString());
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        return files;
    }

        @Override
    public void onPreMerge(Repository repo,
            CodeReviewCommit commit,
            ProjectState destProject,
            Branch.NameKey destBranch,
            PatchSet.Id patchSetId) throws MergeValidationException {

    }

    public static Supplier<Repository> repoFromPath(String path) {
        return (Supplier<Repository>) () -> {
            try {
                return new FileRepositoryBuilder()
                        .readEnvironment() // scan environment GIT_* variables
                        .findGitDir(Paths.get(path).toFile())
                        .build();
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            }
        };
    }

    public static Supplier<Repository> repoFromProjectKey(Project.NameKey project, GitRepositoryManager Manager) {
        return (Supplier<Repository>) () -> {
            try {
                return Manager.openRepository(project);
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            }
        };
    }

}
