package com.googlesource.gerrit.plugins.testplugin;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.AllProjectsConfig;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;

public class MyInitStep implements InitStep {
    private final String pluginName;
    private final ConsoleUI ui;
    private final AllProjectsConfig allProjectsConfig;

    @Inject
    public MyInitStep(@PluginName String pluginName, ConsoleUI ui,
            AllProjectsConfig allProjectsConfig) {
        this.pluginName = pluginName;
        this.ui = ui;
        this.allProjectsConfig = allProjectsConfig;
    }

    @Override
    public void run() throws Exception {
    }

    @Override
    public void postRun() throws Exception {
        ui.message("\n");
        ui.header(pluginName + " Integration");
        boolean enabled = ui.yesno(true, "By default enabled for all projects");
        Config cfg = allProjectsConfig.load().getConfig();
        if (enabled) {
            cfg.setBoolean("plugin", pluginName, "enabled", enabled);
        } else {
            cfg.unset("plugin", pluginName, "enabled");
        }
        allProjectsConfig.save(pluginName, "Initialize " + pluginName + " Integration");
    }
}
