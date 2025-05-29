// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.zps.zest.browser

import com.intellij.openapi.Disposable
import java.io.InputStream

//@Deprecated(replaceWith = ReplaceWith("com.intellij.ui.jcef.utils.JBCefStreamResourceHandler"),
//            message = "Use JBCefStreamResourceHandler instead",
//            level = DeprecationLevel.WARNING)
class CefStreamResourceHandler(
  myStream: InputStream,
  myMimeType: String,
  parent: Disposable,
  headers: Map<String, String> = mapOf(),
) : JBCefStreamResourceHandler(myStream, myMimeType, parent, headers)