# Optional SVG/GIF support in Markwon image module — not bundled, safe to ignore at build time
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**

# JLatexMath uses some reflection internally
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Prism4j generated grammar locator and grammars
-keep class io.noties.prism4j.** { *; }
-keep class com.chatscroll.app.Prism4jGrammarLocator { *; }

# kotlinx-serialization (rules also bundled with the library)
-keepclassmembers class com.chatscroll.app.data.** { *; }
