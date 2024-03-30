package io.github.tabilzad.ktor

import arrow.meta.dsl.config.ConfigSyntax
import io.github.tabilzad.ktor.visitors.ExpressionsVisitor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.resolve.checkers.DeclarationCheckerContext
import org.jetbrains.kotlin.utils.addToStdlib.UnsafeCastFunction
import org.jetbrains.kotlin.utils.addToStdlib.safeAs


/**
 * This is the main phase of the plugin that starts going through
 * all declarations in the code and search for functions annotated with @KtorDocs.
 * For such functions it invokes the ExpressionVisitor which recursively walks through
 * all expressions in the function body to extract Ktor dsl related data
 * and convert in to Open API specification
 */
fun ConfigSyntax.swaggerExtensionPhase(config: PluginConfiguration) = declarationChecker { ktDeclaration: KtDeclaration,
                                                                                               declarationDescriptor: DeclarationDescriptor,
                                                                                               declarationCheckerContext: DeclarationCheckerContext ->

    if (config.isEnabled) {
        ktDeclaration.startVisiting(declarationCheckerContext, config)
    }
}

private fun KtDeclaration.startVisiting(
    declarationCheckerContext: DeclarationCheckerContext,
    configuration: PluginConfiguration,
) {
    if (hasAnnotation(KtorDocs::class.simpleName)) {
        val context = declarationCheckerContext.trace.bindingContext

        val expressionsVisitor = ExpressionsVisitor(configuration, context)
        val rawRoutes = accept(expressionsVisitor, null)

        val routes: List<DocRoute> = if (rawRoutes.any { it !is DocRoute }) {
            val (routes, endpoints) = rawRoutes.partition { it is DocRoute }
            val docRoutes = routes as List<DocRoute>
            docRoutes.plus(DocRoute("/", endpoints.toMutableList()))
        } else {
            rawRoutes as List<DocRoute>
        }

        val components = expressionsVisitor.classNames
            .associateBy { it.fqName ?: "UNKNOWN" }
            .mapValues { (k, v) ->
                val objectDefinition = if (v.properties.isNullOrEmpty()) {
                    v.copy(properties = null)
                } else {
                    v
                }
                objectDefinition
            }

        convertInternalToOpenSpec(
            routes = routes,
            configuration = configuration,
            schemas = components
        ).serializeAndWriteTo(configuration)
    }
}

private fun convertInternalToOpenSpec(
    routes: List<DocRoute>,
    configuration: PluginConfiguration,
    schemas: Map<String, OpenApiSpec.ObjectType>
): OpenApiSpec {
    val reducedRoutes = routes
        .map {
            reduce(it)
                .cleanPaths()
                .convertToSpec()
        }
        .reduce { acc, route ->
            acc.plus(route)
        }

    return OpenApiSpec(
        info = OpenApiSpec.Info(
            title = configuration.title,
            description = configuration.description,
            version = configuration.version
        ), paths = reducedRoutes,
        components = OpenApiComponents(schemas)
    )
}

@OptIn(UnsafeCastFunction::class)
fun KtAnnotated.hasAnnotation(
    vararg annotationNames: String?
): Boolean {
    val names = annotationNames.toHashSet()
    val predicate: (KtAnnotationEntry) -> Boolean = {
        it.typeReference?.typeElement?.safeAs<KtUserType>()?.referencedName in names
    }
    return annotationEntries.any(predicate)
}

@OptIn(UnsafeCastFunction::class)
fun KtAnnotated.findAnnotation(
    annotationName: String?
): KtAnnotationEntry? = annotationEntries.find {
    it.typeReference?.typeElement?.safeAs<KtUserType>()?.referencedName == annotationName
}