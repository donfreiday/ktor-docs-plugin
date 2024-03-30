package io.github.tabilzad.ktor

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

fun locateOrCreateSwaggerFile(
    configuration: PluginConfiguration,
): File = configuration.run {
    if (filePath != null) {
        File("$filePath/openapi.$format")
    } else if (saveInBuild) {
        File("$buildPath/openapi").apply {
            mkdir()
        }.let { dir ->
            File(dir.path + "/openapi.$format")
        }
    } else {
        val filePath = modulePath.split("/main").first() + "/main"
        val resourcesDir = File(filePath).listFiles()?.firstNotNullOf {
            if (listOf("res", "resources").contains(it.name)) {
                it.name
            } else {
                null
            }
        } ?: throw IllegalAccessException("error")
        File("$filePath/$resourcesDir/raw/").apply {
            mkdir()
        }.let { dir ->
            File(dir.path + "/openapi.$format")
        }
    }
}

fun OpenApiSpec.serializeAndWriteTo(configuration: PluginConfiguration) {
    val file = locateOrCreateSwaggerFile(configuration)

    getJacksonBy(configuration.format).let { mapper ->
        val new = try {
            val existingSpec = mapper.readValue<OpenApiSpec>(file)
            existingSpec.mergeAndResolveConflicts(this)
        } catch (ex: Exception) {
            this
        }
        val sorted = new.copy(
            components = new.components.copy(
                schemas = new.components.schemas.toSortedMap()
            )
        )
        file.writeText(mapper.writeValueAsString(sorted))
    }
}

fun OpenApiSpec.mergeAndResolveConflicts(newSpec: OpenApiSpec): OpenApiSpec {

    val (duplicatePaths, newDistinctPaths) = newSpec.paths.entries.partition { entry ->
        paths.containsKey(entry.key)
    }.let { (first, second) ->
        first.associate { it.key to it.value } to second.associate { it.key to it.value }
    }

    val resolvedConflicts = duplicatePaths.mapValues { (path, endpoint) ->
        endpoint.mapValues { (method, endpointValue) ->
            paths[path]?.get(method)?.let {
                endpointValue.copy(
                    tags = (it.tags merge endpointValue.tags)?.toSet()?.toList()
                )
            } ?: endpointValue
        }
    }
    return copy(
        paths = paths + newDistinctPaths + resolvedConflicts,
        components = OpenApiComponents(components.schemas.plus(newSpec.components.schemas))
    )
}

fun getJacksonBy(format: String): ObjectMapper = when (format) {
    "yaml" -> ObjectMapper(YAMLFactory()).registerKotlinModule()
    else -> jacksonObjectMapper()
}.apply {
    enable(SerializationFeature.INDENT_OUTPUT)
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
}