/*
 * Copyright 2015-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.nebula.lint

import groovy.transform.Canonical

import java.nio.file.Files

/**
 * Fixes that implement this marker interface will generate a single patchset per fix
 */
interface RequiresOwnPatchset {}
interface DeletesFile extends RequiresOwnPatchset {}
interface CreatesFile extends RequiresOwnPatchset {}

/**
 * Used to generate a unified diff format of auto-corrections for violations
 */
abstract class GradleLintFix {
    File affectedFile

    abstract int from()
    abstract int to()
    abstract String changes()
}

abstract class GradleLintMultilineFix extends GradleLintFix {
    Range<Integer> affectedLines // 1-based, inclusive

    @Override
    int from() { affectedLines.from }

    @Override
    int to() { affectedLines.to }
}

@Canonical
class GradleLintReplaceWith extends GradleLintMultilineFix {
    Integer fromColumn // the first affected column of the first line (1-based, inclusive)
    Integer toColumn // the last affected column of the last line (1-based, exclusive)
    String changes

    GradleLintReplaceWith(File affectedFile, Range<Integer> affectedLines, Integer fromColumn, Integer toColumn, String changes) {
        this.affectedFile = affectedFile
        this.affectedLines = affectedLines
        this.fromColumn = fromColumn
        this.toColumn = toColumn
        this.changes = changes
    }

    @Override
    String changes() { changes }
}

@Canonical
class GradleLintDeleteLines extends GradleLintMultilineFix {

    GradleLintDeleteLines(File affectedFile, Range<Integer> affectedLines) {
        this.affectedFile = affectedFile
        this.affectedLines = affectedLines
    }

    @Override
    String changes() { null }
}

@Canonical
class GradleLintReplaceAll extends GradleLintMultilineFix {

    String changes

    GradleLintReplaceAll(File affectedFile, String changes) {
        this.affectedFile = affectedFile
        this.affectedLines = 1..affectedFile.readLines().size()
        this.changes = changes
    }

    @Override
    String changes() { changes }
}

@Canonical
class GradleLintInsertAfter extends GradleLintFix {
    Integer afterLine // 1-based
    String changes

    public GradleLintInsertAfter(File affectedFile, Integer afterLine, String changes) {
        this.affectedFile = affectedFile
        this.afterLine = afterLine
        this.changes = changes
    }

    @Override
    int from() { afterLine+1 }

    @Override
    int to() { afterLine }

    @Override
    String changes() { changes }
}

@Canonical
class GradleLintInsertBefore extends GradleLintFix {
    Integer beforeLine // 1-based
    String changes

    public GradleLintInsertBefore(File affectedFile, Integer beforeLine, String changes) {
        this.affectedFile = affectedFile
        this.beforeLine = beforeLine
        this.changes = changes
    }

    @Override
    int from() { beforeLine }

    @Override
    int to() { beforeLine-1 }

    @Override
    String changes() { changes }
}

@Canonical
class GradleLintDeleteFile extends GradleLintMultilineFix implements DeletesFile {
    GradleLintDeleteFile(File affectedFile) {
        this.affectedFile = affectedFile
        def numberOfLines = Files.isSymbolicLink(affectedFile.toPath()) ? 1 : affectedFile.readLines().size()
        this.affectedLines = 1..numberOfLines
    }

    @Override
    String changes() { null }
}

@Canonical
class GradleLintCreateFile extends GradleLintInsertBefore implements CreatesFile {
    FileType fileType

    GradleLintCreateFile(File newFile, String changes, FileType fileType = FileType.Regular) {
        super(newFile, 1, changes)
        this.fileType = fileType
    }
}