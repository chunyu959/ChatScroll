package com.chatscroll.app

import android.app.Application
import io.noties.prism4j.annotations.PrismBundle

/**
 * Application entry. The @PrismBundle annotation triggers code generation of
 * the Prism4j grammar locator used by the syntax highlighting plugin.
 */
@PrismBundle(
    include = [
        "c", "clike", "clojure", "cpp", "csharp", "css", "dart", "git", "go",
        "groovy", "java", "javascript", "json", "kotlin", "latex", "makefile",
        "markdown", "markup", "python", "scala", "sql", "swift", "yaml"
    ],
    grammarLocatorClassName = ".Prism4jGrammarLocator"
)
class ChatScrollApp : Application()
