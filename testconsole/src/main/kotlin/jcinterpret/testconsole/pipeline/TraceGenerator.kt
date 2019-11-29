package jcinterpret.testconsole.pipeline

import jcinterpret.core.ExecutionConfig
import jcinterpret.core.JavaConcolicInterpreterFactory
import jcinterpret.core.TooManyContextsException
import jcinterpret.core.control.UnsupportedLanguageFeature
import jcinterpret.core.descriptors.DescriptorLibraryFactory
import jcinterpret.core.descriptors.UnresolvableDescriptorException
import jcinterpret.core.descriptors.qualifiedSignature
import jcinterpret.core.source.SourceLibraryFactory
import jcinterpret.core.trace.EntryPointExecutionTraces
import jcinterpret.document.ConfigDocument
import jcinterpret.document.DocumentUtils
import jcinterpret.entry.EntryPointFinder
import jcinterpret.parser.Parser
import jcinterpret.testconsole.utils.FileUtils
import jcinterpret.testconsole.utils.Project
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.streams.toList

fun main(args: Array<String>) {
    if (args.count() != 1)
        error("One argument is expected listing the path to a valid config document")

    val docPath = Paths.get(args[0])

    if (!Files.exists(docPath) || !Files.isRegularFile(docPath))
        error("The passed argument is not a path to a file")

    val document = DocumentUtils.readJson(docPath, ConfigDocument::class)
    ExecutionConfig.loggingEnabled = document.loggingEnabled
    ExecutionConfig.maxLoopExecutions = document.maxLoopExecutions
    ExecutionConfig.maxRecursiveCalls = document.maxRecursiveCalls

    val root = docPath.parent
    val projectsRoot = root.resolve(document.projectsRoot)
    val output = root.resolve(document.output)
        .resolve(projectsRoot.fileName.toString())

    if (!Files.exists(output))
        Files.createDirectories(output)

    if (!Files.exists(projectsRoot))
        throw IllegalArgumentException("Unknown projects root $projectsRoot")

    val globalLibraries = document.globalLibraries.map { root.resolve(it) }
    val projectLibraries = document.projectLibraries?.map { it.key to it.value.map { root.resolve(it) } }?.toMap()
        ?: emptyMap()

    val projectPaths = Files.list(projectsRoot)
        .filter { Files.isDirectory(it) }
        .toList()
        .sorted()

    val dir = output.resolve("${document.title}_${Instant.now().nano}")
    Files.createDirectory(dir)

    //  Pre-Processing: Parse the projects
    println("Pre-Processing: Parsing the projects")
    val projects = projectPaths.mapNotNull { path ->
        val id = path.fileName.toString()
        val sources = FileUtils.listFiles(path, ".java")
        val directories = FileUtils.listDirectories(path)
        val eps = document.entryPoints?.get(id)?.toList() ?: emptyList()
        val libraries = if (projectLibraries.containsKey(id)) {
            globalLibraries + projectLibraries[id]!!
        } else {
            globalLibraries
        }

        val compilationUnits = Parser.parse(sources, libraries, directories)
        val msg = compilationUnits.flatMap { it.messages.toList() }
            .filter { it.message.contains("Syntax error") || it.message.contains("cannot be resolved") }

        if (msg.isNotEmpty()) {
            println("Ignoring $id")
            return@mapNotNull null
        }

        val descriptorLibrary = DescriptorLibraryFactory.build(compilationUnits, libraries)
        val sourceLibrary = SourceLibraryFactory.build(compilationUnits)

        val entries = EntryPointFinder.find(compilationUnits, eps)

        if (entries.isEmpty())
            Unit

        return@mapNotNull Project(
            id,
            path,
            compilationUnits,
            descriptorLibrary,
            sourceLibrary,
            entries
        )
    }.toList()

    val f = mutableListOf<CompletableFuture<Void>>()

    println("Generating Execution Traces")
    projects
        .forEach { project ->
        try {
            println("Executing ${project.id}")
            val result = project.entries.map { entry ->
                val sig = entry.binding.qualifiedSignature()
                println("\tInvoking ${project.id} $sig ${Date()}")
                val interpreter = JavaConcolicInterpreterFactory.build(sig, project.descriptorLibrary, project.sourceLibrary)
                val traces = interpreter.execute()
                return@map entry to traces
            }.toList().toMap()

            val projDir = dir.resolve(project.id)
            Files.createDirectory(projDir)

            println("\tSaving... ${project.id} at ${Date()}")
            f.add(
                CompletableFuture.runAsync {
                    for ((entry, traces) in result) {
                        println("\t\t${entry.binding.qualifiedSignature()}: ${traces.size} traces")

                        val msig = entry.binding.qualifiedSignature()
                        val fout = projDir.resolve(msig.toString().replace("/", ".") + ".ser")
                        Files.createFile(fout)

                        val document = EntryPointExecutionTraces (
                            msig,
                            traces.toTypedArray()
                        )

                        DocumentUtils.writeObject(fout, document)
                    }
                }
            )

        } catch (e: UnsupportedLanguageFeature) {
            println("Removing ${project.id} due to: ${e.msg}")
        } catch (e: TooManyContextsException) {
            System.err.println("Too many contexts in ${project.id}")
        } catch (e: UnresolvableDescriptorException) {
            System.err.println("Cannot resolve descriptor for type ${e.sig}")
        }
    }

    println("Finishing writing to disk ...")
    CompletableFuture.allOf(*f.filterNot { it.isDone }.toTypedArray())
        .get()

    println("Finished")
}