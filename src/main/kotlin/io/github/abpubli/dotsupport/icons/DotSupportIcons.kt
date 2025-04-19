package io.github.abpubli.dotsupport.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DotSupportIcons {
    @JvmField // @JvmField is often useful for compatibility with Java and some platform mechanisms
    val DOT_ACTION_ICON: Icon = IconLoader.getIcon("/icons/dotFile.svg", DotSupportIcons::class.java)
}