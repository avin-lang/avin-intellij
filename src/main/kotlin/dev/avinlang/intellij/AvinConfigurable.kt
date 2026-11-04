package dev.avinlang.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings | Languages & Frameworks | Avin. */
class AvinConfigurable : BoundConfigurable("Avin") {
    private val settings = AvinSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Language Server") {
            row("Server path:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Avin Executable"))
                    .bindText(settings::serverPath)
                    .align(AlignX.FILL)
                    .comment("The avin executable, run as '&lt;path&gt; lsp --stdio'. A bare name is looked up on PATH.")
            }
            row("Repository:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir().withTitle("Avin Repository Checkout"))
                    .bindText(settings::repository)
                    .align(AlignX.FILL)
                    .comment(
                        "A checkout of the Avin repository to run the server from instead: " +
                            "'sysl run &lt;repository&gt;/compiler -- lsp --stdio', with AVIN_ROOT set to the checkout " +
                            "and the checkout as the working directory. Empty uses the server path.",
                    )
            }
        }
    }

    override fun apply() {
        val before = settings.serverPath to settings.repository
        super.apply()
        if (before != (settings.serverPath to settings.repository)) restartAvinServers()
    }
}
