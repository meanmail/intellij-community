// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.dsl

import groovy.transform.CompileStatic
import org.jetbrains.plugins.gradle.highlighting.GradleHighlightingBaseTest
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.util.ResolveTest
import org.junit.Test

import static org.jetbrains.plugins.gradle.service.resolve.GradleCommonClassNames.GRADLE_API_ARTIFACT_HANDLER
import static org.jetbrains.plugins.groovy.lang.resolve.delegatesTo.GrDelegatesToUtilKt.getDelegatesToInfo

@CompileStatic
class GradleArtifactsTest extends GradleHighlightingBaseTest implements ResolveTest {

  @Test
  void artifactsTest() {
    importProject("")
    'artifacts closure delegate'()
  }

  @Override
  protected List<String> getParentCalls() {
    return super.getParentCalls() + 'buildscript'
  }

  void 'artifacts closure delegate'() {
    doTest('artifacts { <caret> }') {
      def closure = elementUnderCaret(GrClosableBlock)
      def delegatesToInfo = getDelegatesToInfo(closure)
      assert delegatesToInfo.typeToDelegate.equalsToText(GRADLE_API_ARTIFACT_HANDLER)
      assert delegatesToInfo.strategy == 1
    }
  }
}
