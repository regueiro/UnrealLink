import com.jetbrains.rd.generator.gradle.RdgenParams
import com.jetbrains.rd.generator.gradle.RdgenTask

repositories {
    mavenCentral()
    mavenLocal()
    jcenter()
    maven { setUrl("https://cache-redirector.jetbrains.com/plugins.gradle.org") }
    maven { setUrl("https://cache-redirector.jetbrains.com/www.myget.org/F/rd-snapshots/maven") }
}

plugins {
    id("java")
    kotlin("jvm")
    id("com.jetbrains.rdgen")
}

dependencies {
    val rdLibDirectory by rootProject.extra.properties

    implementation(kotlin("stdlib"))

    compile(files("$rdLibDirectory/rider-model.jar"))
    compile(group = "com.jetbrains.rd", name = "rd-gen", version = rdGenPluginVersion)
}

val rdLibDirectory: File by rootProject.extra
val repoRoot: File by rootProject.extra

val modelDir = File(repoRoot, "protocol/src/main/kotlin/model")
val hashBaseDir = File(repoRoot, "build/rdgen")

configure<RdgenParams> {
    verbose = true
    classpath("$rdLibDirectory/rider-model.jar", sourceSets.main.get().output)
}

tasks {
    //    val unrealEditorCppOutput = File(repoRoot, "src/cpp/Source/RiderLink/Private/RdEditorProtocol")
    val unrealEditorCppOutput = File("C:\\Work\\UnrealEngine\\Engine\\Plugins\\Developer\\RiderLink\\Source\\RiderLink\\Private\\RdEditorProtocol")
    val csEditorOutput = File(repoRoot, "src/dotnet/ReSharperPlugin.resharper_unreal/model/RdEditorProtocol")
    val csRiderOutput = File(repoRoot, "src/dotnet/ReSharperPlugin.resharper_unreal/model/RdRiderProtocol")
    val csLibraryOutput = File(repoRoot, "src/dotnet/ReSharperPlugin.resharper_unreal/model/Library")
    val ktOutput = File(repoRoot, "src/rider/main/kotlin/com/jetbrains/rider/model/RdRiderProtocol")


    val generateRiderModel by creating(RdgenTask::class) {
        configure<RdgenParams> {
            // NOTE: classpath is evaluated lazily, at execution time, because it comes from the unzipped
            // intellij SDK, which is extracted in afterEvaluate

            sources("$modelDir/rider")
            packages = "model.rider"
            hashFolder = "$hashBaseDir/rider"

            generator {
                language = "kotlin"
                transform = "asis"
                root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
                //            namespace = "com.jetbrains.rider.plugins.unreal"
                namespace = "com.jetbrains.rider.model"
                directory = "$ktOutput"

            }

            generator {
                language = "csharp"
                transform = "reversed"
                root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
                namespace = "JetBrains.Rider.Model"
                directory = "$csRiderOutput"
            }
            properties["model.out.src.rider.csharp.dir"] = "$csRiderOutput"
            properties["model.out.src.rider.kotlin.dir"] = "$ktOutput"
        }
    }


    val generateEditorPluginModel by creating(RdgenTask::class) {
        configure<RdgenParams> {
            sources("$modelDir/editorPlugin")
            hashFolder = "$hashBaseDir/editorPlugin"
            packages = "model.editorPlugin"

            properties["model.out.src.editorPlugin.csharp.dir"] = "$csEditorOutput"
            properties["model.out.src.editorPlugin.cpp.dir"] = "$unrealEditorCppOutput"
        }
    }

    val generateUE4Lib by creating(RdgenTask::class) {
        configure<RdgenParams> {
            sources("$modelDir/lib/ue4")
            hashFolder = "$hashBaseDir/lib/ue4"
            packages = "model.lib.ue4"

            properties["model.out.src.lib.ue4.csharp.dir"] = "$csLibraryOutput"
            properties["model.out.src.lib.ue4.cpp.dir"] = "$unrealEditorCppOutput"
            properties["model.out.src.lib.ue4.kt.dir"] = "$ktOutput"
        }
    }

    val generateModel by creating {
        group = "protocol"
        description = "Generates protocol models."
        dependsOn(generateRiderModel, generateEditorPluginModel, generateUE4Lib)
    }

    withType<RdgenTask> {
        dependsOn(build)
    }
}