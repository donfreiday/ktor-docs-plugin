package io.github.tabilzad.ktor

import io.github.tabilzad.ktor.OpenApiSpec.*
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import java.io.OutputStream
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

fun MemberScope.forEachVariable(predicate: (PropertyDescriptor) -> Unit) {
    getDescriptorsFiltered(DescriptorKindFilter.VARIABLES)
        .map { it.original }
        .filterIsInstance<PropertyDescriptorImpl>()
        .filter {
            it.backingField?.annotations?.hasAnnotation(FqName("kotlin.jvm.Transient"))?.let { exists ->
                !exists
            } ?: true
        }.forEach {
            predicate(it)
        }
}

val Iterable<OpenApiSpec.ObjectType>.names get() = mapNotNull { it.name }
fun reduce(e: DocRoute): List<KtorRouteSpec> = e.children.flatMap { child ->
    when (child) {
        is DocRoute -> {
            reduce(
                child.copy(path = e.path + child.path)
            )
        }

        is EndPoint -> {
            listOf(
                KtorRouteSpec(
                    path = e.path + (child.path ?: ""),
                    method = child.method,
                    body = child.body
                )
            )
        }

        else -> {
            emptyList()
        }
    }
}

fun List<KtorRouteSpec>.cleanPaths() = map {
    it.copy(path = it.path.replace("//", "/"))
}

fun List<KtorRouteSpec>.convertToSpec() = associate {
    it.path to mapOf(
        it.method to Path()
    ).run {
        compute(
            { addPathParams(it) },
            { addPostBody(it) }
        )
    }
}

fun Map<String, Path>.compute(
    vararg modifiers: () -> List<OpenApiSpecParam>
) = modifiers.flatMap { it() }.let {
    this.plus(mapOf("parameters" to it))
}

private fun addPathParams(it: KtorRouteSpec): List<PathParam> {
    val params = "\\{([^}]*)}".toRegex().findAll(it.path).toList()
    return if (params.isNotEmpty()) {
        params.map {
            PathParam(
                name = it.groups[1]?.value ?: "",
                `in` = "path",
                required = true,
                type = "string"
            )
        }
    } else {
        emptyList()
    }
}

private fun addPostBody(it: KtorRouteSpec): List<BodyParam> {
    return if (it.method == "post") {
        val ref = it.body.name
        listOf(
            BodyParam(
                name = "request",
                `in` = "body",
                schema = Schema(
                    it.body.type, if (ref == null) {
                        it.body.ref
                    } else "#/definitions/$ref"
                )
            )
        )
    } else {
        emptyList()
    }
}

fun Class<*>.resolveDefinitionTo(obj: ObjectType): ObjectType {
    kotlin.memberProperties.forEach { field ->
        if (field.returnType.jvmErasure.java.isPrimitive) {
            obj.properties?.set(
                field.name, ObjectType(
                    type = field.returnType.jvmErasure.java.simpleName.lowercase(),
                    properties = null
                )
            )
        } else {
            obj.properties?.set(
                field.name, ObjectType(
                    type = "object",
                    properties = mutableMapOf(
                        field.name to field.javaClass.resolveDefinitionTo(ObjectType("object", mutableMapOf()))
                    )
                )
            )
        }
    }
    return obj
}

operator fun OutputStream.plusAssign(str: String) {
    this.write(str.toByteArray())
}

object HttpCodeResolver{
    fun resolve(code: String?): String = codes[code] ?: "200"
    private val codes = mapOf(
        "Continue" to "100",
        "SwitchingProtocols" to "101",
        "Processing" to "102",
        "OK" to "200",
        "Created" to "201",
        "Accepted" to "202",
        "NonAuthoritativeInformation" to "203",
        "NoContent" to "204",
        "ResetContent" to "205",
        "PartialContent" to "206",
        "MultiStatus" to "207",
        "MultipleChoices" to "300",
        "MovedPermanently" to "301",
        "Found" to "302",
        "SeeOther" to "303",
        "NotModified" to "304",
        "UseProxy" to "305",
        "SwitchProxy" to "306",
        "TemporaryRedirect" to "307",
        "PermanentRedirect" to "308",
        "BadRequest" to "400",
        "Unauthorized" to "401",
        "PaymentRequired" to "402",
        "Forbidden" to "403",
        "NotFound" to "404",
        "MethodNotAllowed" to "405",
        "NotAcceptable" to "406",
        "ProxyAuthenticationRequired" to "407",
        "RequestTimeout" to "408",
        "Conflict" to "409",
        "Gone" to "410",
        "LengthRequired" to "411",
        "PreconditionFailed" to "412",
        "PayloadTooLarge" to "413",
        "RequestURITooLong" to "414",
        "UnsupportedMediaType" to "415",
        "RequestedRangeNotSatisfiable" to "416",
        "ExpectationFailed" to "417",
        "UnprocessableEntity" to "422",
        "Locked" to "423",
        "FailedDependency" to "424",
        "UpgradeRequired" to "426",
        "TooManyRequests" to "429",
        "RequestHeaderFieldTooLarge" to "431",
        "InternalServerError" to "500",
        "NotImplemented" to "501",
        "BadGateway" to "502",
        "ServiceUnavailable" to "503",
        "GatewayTimeout" to "504",
        "VersionNotSupported" to "505",
        "VariantAlsoNegotiates" to "506",
        "InsufficientStorage" to "507"
    )

}
