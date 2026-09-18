package photos.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes [text] to [output]: a cinterop `.def` whose paths come from resolved native libraries.
 *
 * A task rather than a file written during configuration, because those paths are only known
 * once `:native` has been resolved -- and resolving lazily is what lets [text]'s provider carry
 * the dependency on the tasks that build them.
 */
@DisableCachingByDefault(because = "Writing a string is cheaper than a cache round trip")
abstract class WriteText : DefaultTask() {
    @get:Input
    abstract val text: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText(text.get())
    }
}
