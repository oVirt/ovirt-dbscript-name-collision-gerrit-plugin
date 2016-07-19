include_defs('//bucklets/gerrit_plugin.bucklet')

gerrit_plugin(
  name = 'ovirt-dbscript-name-collision',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: ovirt-dbscript-name-collision',
    'Gerrit-ApiType: plugin',
    'Gerrit-ApiVersion: 2.12',
  ],
)

# this is required for bucklets/tools/eclipse/project.py to work
java_library(
  name = 'classpath',
  deps = [':ovirt-dbscript-name-collision'],
)

