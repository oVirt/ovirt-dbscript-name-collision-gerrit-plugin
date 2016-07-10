package com.googlesource.gerrit.plugins.testplugin;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class OvirtPatchSetListenerTest {

    private static final String FOLDER_PREFIX = "packaging/dbscripts/upgrade/";

    @Test
    public void testRepo() throws IOException {

        try (Repository repository = openOvirtEngineTest();
             TreeWalk treeWalk = new TreeWalk(repository);
             RevWalk revWalk = new RevWalk(repository)) {
            treeWalk.addTree(revWalk.parseTree(repository.resolve(Constants.HEAD)));
            treeWalk.setFilter(PathFilter.create(FOLDER_PREFIX));
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                if (treeWalk.getDepth() != 3 ) { continue; }
                System.out.println("File is " + treeWalk.getNameString());
            }
        }
    }

    public static Repository openOvirtEngineTest() throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder
                .readEnvironment() // scan environment GIT_* variables
                .findGitDir(Paths.get("/home/rgolan/src/git/ovirt-engine-test").toFile()) // scan up the file system tree
                .build();
        return repository;
    }

}
