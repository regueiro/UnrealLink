package model.editorPlugin

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.*
import com.jetbrains.rd.generator.nova.cpp.Cpp17Generator
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rd.generator.nova.util.syspropertyOrInvalid
import model.lib.ue4.UE4Library.UnrealLogMessage
import java.io.File

@Suppress("unused")
object RdEditorRoot : Root(
        CSharp50Generator(FlowTransform.AsIs, "JetBrains.Platform.UnrealEngine.Model", File(syspropertyOrInvalid("model.out.src.editorPlugin.csharp.dir"))),
        Cpp17Generator(FlowTransform.Reversed, "com.jetbrains.rider.plugins.unrealengine", File(syspropertyOrInvalid("model.out.src.editorPlugin.cpp.dir")))
) {
    init {
        setting(CSharp50Generator.AdditionalUsings) {
            listOf("JetBrains.Unreal.Lib")
        }
    }

    init {
        setting(Cpp17Generator.MarshallerHeaders, listOf("UE4TypesMarshallers.h"))
    }
}

object RdEditorModel : Ext(RdEditorRoot) {
    init {

        property("testConnection", int.nullable)
        signal("unrealLog", UnrealLogMessage)
        property("play", bool)
        call("checkIfBlueprint", string, bool)
    }
}