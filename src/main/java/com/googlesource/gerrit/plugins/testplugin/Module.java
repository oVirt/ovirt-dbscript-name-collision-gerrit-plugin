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
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.server.data.PatchAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.inject.AbstractModule;

import java.util.ArrayList;
import java.util.List;

class Module extends AbstractModule implements EventListener{
    private static final String PREFIX = "packaging/dbscript/upgrade";

    @Override
    protected void configure() {
        // TODO
    }

    @Override public void onEvent(Event event) {
        if (event instanceof PatchSetCreatedEvent) {
            PatchSetCreatedEvent e = (PatchSetCreatedEvent) event;
            // prevent looping by checking the patch was uploaded by the pluign

            // get added files which match prefix
            List<String> files = new ArrayList<>();
            for (PatchAttribute file : e.patchSet.files) {
                if (file.type == Patch.ChangeType.ADDED &&
                        file.file.startsWith(PREFIX)) {
                    files.add(file.file);
                }
            }

            // detect name collision
            //            e.change.

        }
    }
}
