package dev.avinlang.intellij

import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files

/**
 * The Avin TextMate bundle: the grammar, the language configuration (comments, brackets,
 * auto-closing pairs, indentation) and the manifest that names them. The build places it in the
 * plugin's `textmate/avin` directory; the platform hands this extension the plugin's descriptor,
 * which says where the plugin is installed.
 */
class AvinBundleProvider : TextMateBundleProvider, PluginAware {
    private var plugin: PluginDescriptor? = null

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        plugin = pluginDescriptor
    }

    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> {
        val textmate = plugin?.pluginPath?.resolve("textmate") ?: return emptyList()
        return listOf("Avin" to "avin", "Sysl" to "sysl").mapNotNull { (name, dir) ->
            val bundle = textmate.resolve(dir)
            if (Files.isDirectory(bundle)) TextMateBundleProvider.PluginBundle(name, bundle) else null
        }
    }
}
