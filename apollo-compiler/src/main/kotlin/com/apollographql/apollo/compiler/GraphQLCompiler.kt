package com.apollographql.apollo.compiler

import com.apollographql.apollo.compiler.codegen.kotlin.GraphQLKompiler
import com.apollographql.apollo.compiler.ir.CodeGenerationContext
import com.apollographql.apollo.compiler.ir.CodeGenerationIR
import com.apollographql.apollo.compiler.ir.ScalarType
import com.apollographql.apollo.compiler.ir.TypeDeclaration
import com.apollographql.apollo.compiler.operationoutput.OperationOutputWriter
import com.squareup.javapoet.JavaFile
import java.io.File

class GraphQLCompiler {
  fun write(args: Arguments) {
    val ir = args.ir
    val customTypeMap = args.customTypeMap.supportedTypeMap(ir.typesUsed)
    val operationIdGenerator = args.operationIdGenerator
    val context = CodeGenerationContext(
        reservedTypeNames = emptyList(),
        typeDeclarations = ir.typesUsed,
        customTypeMap = customTypeMap,
        operationIdGenerator = operationIdGenerator,
        nullableValueType = args.nullableValueType,
        ir = ir,
        useSemanticNaming = args.useSemanticNaming,
        generateModelBuilder = args.generateModelBuilder,
        useJavaBeansSemanticNaming = args.useJavaBeansSemanticNaming,
        suppressRawTypesWarning = args.suppressRawTypesWarning,
        generateVisitorForPolymorphicDatatypes = args.generateVisitorForPolymorphicDatatypes
    )

    if (args.generateKotlinModels) {
      GraphQLKompiler(
          ir = ir,
          customTypeMap = args.customTypeMap,
          operationIdGenerator = args.operationIdGenerator,
          useSemanticNaming = args.useSemanticNaming,
          generateAsInternal = args.generateAsInternal,
          kotlinMultiPlatformProject = args.kotlinMultiPlatformProject,
          enumAsSealedClassPatternFilters = args.enumAsSealedClassPatternFilters.map { it.toRegex() }
      ).write(args.outputDir)
    } else {
      ir.writeJavaFiles(
          context = context,
          outputDir = args.outputDir
      )
    }

    args.operationOutputFile?.let { operationOutputFile ->
      val dir = operationOutputFile.parentFile
      dir.mkdirs()

      val operationOutput = OperationOutputWriter(args.operationIdGenerator)
      operationOutput.apply { visit(ir) }.writeTo(operationOutputFile)
    }
  }

  private fun CodeGenerationIR.writeJavaFiles(context: CodeGenerationContext, outputDir: File) {
    fragments.forEach {
      val typeSpec = it.toTypeSpec(context.copy())
      JavaFile
          .builder(context.ir.fragmentsPackageName, typeSpec)
          .addFileComment(AUTO_GENERATED_FILE)
          .build()
          .writeTo(outputDir)
    }

    typesUsed.supportedTypeDeclarations().forEach {
      val typeSpec = it.toTypeSpec(context.copy())
      JavaFile
          .builder(context.ir.typesPackageName, typeSpec)
          .addFileComment(AUTO_GENERATED_FILE)
          .build()
          .writeTo(outputDir)
    }

    if (context.customTypeMap.isNotEmpty()) {
      val typeSpec = CustomEnumTypeSpecBuilder(context.copy()).build()
      JavaFile
          .builder(context.ir.typesPackageName, typeSpec)
          .addFileComment(AUTO_GENERATED_FILE)
          .build()
          .writeTo(outputDir)
    }

    operations.map { OperationTypeSpecBuilder(it, fragments, context.useSemanticNaming) }
        .forEach {
          val packageName = it.operation.packageName
          val typeSpec = it.toTypeSpec(context.copy())
          JavaFile
              .builder(packageName, typeSpec)
              .addFileComment(AUTO_GENERATED_FILE)
              .build()
              .writeTo(outputDir)
        }
  }

  private fun List<TypeDeclaration>.supportedTypeDeclarations() =
      filter { it.kind == TypeDeclaration.KIND_ENUM || it.kind == TypeDeclaration.KIND_INPUT_OBJECT_TYPE }

  private fun Map<String, String>.supportedTypeMap(typeDeclarations: List<TypeDeclaration>): Map<String, String> {
    val idScalarTypeMap = ScalarType.ID.name to (this[ScalarType.ID.name] ?: ClassNames.STRING.toString())
    return typeDeclarations.filter { it.kind == TypeDeclaration.KIND_SCALAR_TYPE }
        .associate { it.name to (this[it.name] ?: ClassNames.OBJECT.toString()) }
        .plus(idScalarTypeMap)
  }

  companion object {
    private const val AUTO_GENERATED_FILE = "AUTO-GENERATED FILE. DO NOT MODIFY.\n\n" +
        "This class was automatically generated by Apollo GraphQL plugin from the GraphQL queries it found.\n" +
        "It should not be modified by hand.\n"
  }

  data class Arguments(
      val ir: CodeGenerationIR,
      val outputDir: File,
      val customTypeMap: Map<String, String>,
      val operationIdGenerator: OperationIdGenerator = OperationIdGenerator.Sha256(),
      val useSemanticNaming: Boolean,
      val packageNameProvider: PackageNameProvider,
      val generateKotlinModels: Boolean = false,
      val operationOutputFile: File? = null,
      val generateAsInternal: Boolean = false,
      // only if generateKotlinModels = true
      val enumAsSealedClassPatternFilters: List<String>,
      // only if generateKotlinModels = false
      val nullableValueType: NullableValueType,
      // only if generateKotlinModels = false
      val generateModelBuilder: Boolean,
      // only if generateKotlinModels = false
      val useJavaBeansSemanticNaming: Boolean,
      // only if generateKotlinModels = false
      val suppressRawTypesWarning: Boolean,
      // only if generateKotlinModels = false
      val generateVisitorForPolymorphicDatatypes: Boolean = false,
      // indicates if target project is MPP
      val kotlinMultiPlatformProject: Boolean = false
  )
}
