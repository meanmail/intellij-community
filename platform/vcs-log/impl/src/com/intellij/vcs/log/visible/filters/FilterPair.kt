// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.visible.filters

import com.intellij.vcs.log.VcsLogFilter

data class FilterPair<F1 : VcsLogFilter, F2 : VcsLogFilter>(val filter1: F1?, val filter2: F2?)