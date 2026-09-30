package com.igloo.blindpenguincoder.data.model

import java.io.File
import java.util.jar.JarFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire models checked against `docs/openapi.json`, so a spec sync that the models were not
 * brought along with fails here instead of on a screen.
 *
 * Roots are the routes production calls, not class names: each entry pairs a route with the
 * serializer its response is decoded with, and its request body with the serializer it is encoded
 * with. That catches a model reusing the wrong schema as well as a field that drifted, and it makes
 * the Kotlin names that lag the spec's (`MovieCastMember` for `MovieCastCredit`) irrelevant.
 *
 * The walk follows `$ref`, merges `allOf`, reads OpenAPI 3.1 `type` arrays for nullability, and
 * accepts a flat-decoded `oneOf` (`ContinueWatchingItem`) when some variant declares each field.
 * A response model may omit fields — it carries only what a screen reads, the rest left to
 * `ignoreUnknownKeys` — but every field it does carry must exist, match in kind, be non-null only
 * where the schema never sends null (`explicitNulls = false` and no `coerceInputValues`, so a null
 * breaks even a defaulted field), and be required whenever the model has no default for it.
 *
 * What this cannot catch: a spec that is itself wrong about the server. That has happened
 * (`Chapter.movie_id` was once declared `SqlNullInt64` while the server sent a number) and only a
 * device run found it. When the server is confirmed right against the main repository, record
 * the field in [knownSpecDeviations] rather than bending the model to the document.
 */
class OpenApiContractTest {

    @Test
    fun everyCalledRouteExistsInTheSpec() {
        val missing = routes.filter { spec.operation(it) == null }.map { it.label }
        assertTrue("Routes not in docs/openapi.json:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun everyModelMatchesItsSchema() {
        val walker = walkEveryRoot()

        val deviationsSeen = mutableSetOf<String>()
        val problems = walker.problems.filterNot { problem ->
            val key = knownSpecDeviations.keys.firstOrNull { problem.field == it }
            key?.also { deviationsSeen += it } != null
        }
        val stale = knownSpecDeviations.keys - deviationsSeen
        assertTrue(
            "Models that drifted from docs/openapi.json:\n${problems.joinToString("\n")}",
            problems.isEmpty(),
        )
        assertTrue(
            "knownSpecDeviations entries the spec no longer contradicts, remove them: $stale",
            stale.isEmpty(),
        )
    }

    /** The rule since 2026-09-21: a model exists only once production calls its route. */
    @Test
    fun everyWireModelIsReachedFromACalledRoute() {
        val unreached = serializableModelNames() - walkEveryRoot().visited
        assertTrue(
            "Models no called route decodes or encodes; delete them or add the route:\n" +
                unreached.sorted().joinToString("\n"),
            unreached.isEmpty(),
        )
    }

    private fun walkEveryRoot(): Walker {
        val walker = Walker()
        routes.forEach { route ->
            val operation = spec.operation(route) ?: return@forEach
            route.response?.let { serializer ->
                walker.root(route.label, serializer, spec.responseSchema(operation), Direction.Decode)
            }
            route.body?.let { serializer ->
                walker.root(route.label, serializer, spec.requestSchema(operation), Direction.Encode)
            }
        }
        schemaRoots.forEach { (name, serializer) ->
            walker.root(name, serializer, spec.schema(name), Direction.Decode)
        }
        return walker
    }

    /** One mismatch; [field] is `Class.wire_field`, the key [knownSpecDeviations] uses. */
    private class Problem(val root: String, val field: String, val message: String) {
        override fun toString() = "[$root] $field: $message"
    }

    private class Route(
        val method: String,
        val path: String,
        val response: KSerializer<*>? = null,
        val body: KSerializer<*>? = null,
    ) {
        val label = "$method $path"
    }

    private enum class Direction { Decode, Encode }

    /** A flattened schema: `$ref` followed, `allOf` merged, nullability lifted out of the type. */
    private class Shape(
        val types: Set<String>,
        val nullable: Boolean,
        val properties: Map<String, JsonObject>,
        val required: Set<String>,
        val items: JsonObject?,
        val enumValues: Set<String>?,
        val variants: List<Shape>,
    ) {
        /** An object that declares no properties (`JsonSuccess.data`, a free-form map). */
        val isOpenObject get() = "object" in types && properties.isEmpty() && variants.isEmpty()
    }

    private inner class Walker {
        val problems = mutableListOf<Problem>()
        val visited = mutableSetOf<String>()
        private val walked = mutableSetOf<Triple<String, JsonObject, Direction>>()
        private var currentRoot = ""

        fun root(label: String, serializer: KSerializer<*>, schema: JsonObject?, direction: Direction) {
            currentRoot = label
            val name = serializer.descriptor.serialName.short()
            if (schema == null) {
                report(name, "the route has no JSON ${if (direction == Direction.Decode) "response" else "request body"}")
            } else {
                walk(serializer.descriptor, schema, direction, name)
            }
        }

        private fun report(field: String, message: String) {
            problems += Problem(currentRoot, field, message)
        }

        private fun walk(descriptor: SerialDescriptor, schema: JsonObject, direction: Direction, at: String) {
            val name = descriptor.serialName.removeSuffix("?")
            if (name.startsWith(KOTLINX_JSON_PREFIX)) return
            val shape = flatten(schema)
            when (val kind = descriptor.kind) {
                is PrimitiveKind -> checkType(kind.wireTypes(), shape, at)
                SerialKind.ENUM -> {
                    visited += name
                    checkType(setOf("string"), shape, at)
                    val kotlinValues = (0 until descriptor.elementsCount).map(descriptor::getElementName)
                    val unknown = shape.enumValues.orEmpty() - kotlinValues.toSet()
                    if (direction == Direction.Decode && unknown.isNotEmpty()) {
                        report(at, "${name.short()} cannot decode $unknown")
                    }
                }
                StructureKind.LIST -> {
                    checkType(setOf("array"), shape, at)
                    val items = shape.items ?: return
                    element(descriptor.getElementDescriptor(0), items, direction, at, required = true)
                }
                StructureKind.MAP -> checkType(setOf("object"), shape, at)
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    visited += name
                    checkType(setOf("object"), shape, at)
                    if (!walked.add(Triple(name, schema, direction))) return
                    if (shape.isOpenObject) return
                    walkClass(descriptor, shape, direction)
                }
                is PolymorphicKind, SerialKind.CONTEXTUAL ->
                    report(at, "${name.short()} has a serializer kind this check cannot read ($kind)")
            }
        }

        private fun walkClass(descriptor: SerialDescriptor, shape: Shape, direction: Direction) {
            val owner = descriptor.serialName.removeSuffix("?").short()
            val variants = shape.variants.ifEmpty { listOf(shape) }
            val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
            names.forEachIndexed { index, field ->
                val at = "$owner.$field"
                val declaring = variants.filter { field in it.properties }
                if (declaring.isEmpty()) {
                    report(at, "not in the schema")
                    return@forEachIndexed
                }
                val optional = descriptor.isElementOptional(index)
                declaring.forEach { variant ->
                    element(
                        descriptor.getElementDescriptor(index),
                        variant.properties.getValue(field),
                        direction,
                        at,
                        required = field in variant.required,
                        optional = optional,
                    )
                }
                // A flat decode of a oneOf: a field every payload must carry has to be in all of them.
                if (direction == Direction.Decode && !optional && declaring.size < variants.size) {
                    report(at, "missing from some oneOf variants, so it needs a default")
                }
            }
            if (direction == Direction.Encode) {
                (shape.required - names.toSet()).forEach { report("$owner.$it", "required but never sent") }
            }
        }

        private fun element(
            child: SerialDescriptor,
            schema: JsonObject,
            direction: Direction,
            at: String,
            required: Boolean,
            optional: Boolean = false,
        ) {
            val shape = flatten(schema)
            when (direction) {
                Direction.Decode -> {
                    if (!child.isNullable && shape.nullable) report(at, "the schema allows null, the model does not")
                    if (!optional && !required) report(at, "optional in the schema, so it needs a default")
                }
                // explicitNulls = false drops a null, and encodeDefaults is off.
                Direction.Encode -> if (required && (optional || child.isNullable)) {
                    report(at, "required by the schema but can be left out")
                }
            }
            walk(child, schema, direction, at)
        }

        private fun checkType(accepted: Set<String>, shape: Shape, at: String) {
            if (shape.types.isNotEmpty() && shape.types.none { it in accepted }) {
                report(at, "the schema says ${shape.types}, the model reads $accepted")
            }
        }
    }

    private fun PrimitiveKind.wireTypes(): Set<String> = when (this) {
        PrimitiveKind.BOOLEAN -> setOf("boolean")
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> setOf("integer")
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> setOf("number", "integer")
        PrimitiveKind.CHAR, PrimitiveKind.STRING -> setOf("string")
    }

    private fun flatten(schema: JsonObject): Shape {
        val resolved = spec.resolve(schema)
        val oneOf = (resolved["oneOf"] ?: resolved["anyOf"])?.jsonArray?.map { it.jsonObject }
        if (oneOf != null) {
            val (nulls, rest) = oneOf.partition { spec.resolve(it).typeNames() == setOf("null") }
            if (rest.size == 1) {
                val inner = flatten(rest.single())
                return inner.copy(nullable = inner.nullable || nulls.isNotEmpty())
            }
            return Shape(
                types = setOf("object"),
                nullable = nulls.isNotEmpty(),
                properties = emptyMap(),
                required = emptySet(),
                items = null,
                enumValues = null,
                variants = rest.map(::flatten),
            )
        }
        val parts = resolved["allOf"]?.jsonArray?.map { flatten(it.jsonObject) }.orEmpty()
        val ownTypes = resolved.typeNames()
        val ownProperties = resolved["properties"]?.jsonObject
            ?.mapValues { (_, value) -> value.jsonObject }.orEmpty()
        val ownRequired = resolved["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        // allOf members all apply; a later member's property overrides an earlier, looser one.
        val properties = parts.fold(emptyMap<String, JsonObject>()) { acc, part -> acc + part.properties } +
            ownProperties
        return Shape(
            types = (ownTypes - "null").ifEmpty { parts.flatMap { it.types }.toSet() },
            nullable = "null" in ownTypes || resolved["nullable"]?.jsonPrimitive?.content == "true",
            properties = properties,
            required = parts.flatMap { it.required }.toSet() + ownRequired,
            items = resolved["items"]?.jsonObject ?: parts.firstNotNullOfOrNull { it.items },
            enumValues = resolved["enum"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet(),
            variants = parts.flatMap { it.variants },
        )
    }

    private fun Shape.copy(nullable: Boolean) =
        Shape(types, nullable, properties, required, items, enumValues, variants)

    private fun JsonObject.typeNames(): Set<String> = when (val type = this["type"]) {
        null -> emptySet()
        is JsonArray -> type.map { it.jsonPrimitive.content }.toSet()
        else -> setOf(type.jsonPrimitive.content)
    }

    private fun String.short() = substringAfterLast('.')

    private class Spec(private val root: JsonObject) {
        fun operation(route: Route): JsonObject? =
            root["paths"]?.jsonObject?.get(route.path)?.jsonObject?.get(route.method.lowercase())?.jsonObject

        fun schema(name: String): JsonObject = resolve(ref("#/components/schemas/$name"))

        fun responseSchema(operation: JsonObject): JsonObject? {
            val responses = operation["responses"]?.jsonObject ?: return null
            val success = responses.entries.firstOrNull { it.key.startsWith("2") }?.value ?: return null
            return resolve(success.jsonObject).jsonContent()
        }

        fun requestSchema(operation: JsonObject): JsonObject? =
            operation["requestBody"]?.let { resolve(it.jsonObject).jsonContent() }

        fun resolve(node: JsonObject): JsonObject {
            var current = node
            while (true) {
                val ref = current["\$ref"]?.jsonPrimitive?.content ?: return current
                current = ref.removePrefix("#/").split('/')
                    .fold(root as JsonElement) { acc, key -> acc.jsonObject.getValue(key) }.jsonObject
            }
        }

        private fun JsonObject.jsonContent(): JsonObject? =
            this["content"]?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject

        private fun ref(pointer: String) = JsonObject(mapOf("\$ref" to JsonPrimitive(pointer)))
    }

    /**
     * Every `@Serializable` class with properties in this package, found by its generated serializer
     * and named by its serial name, which is what the walk records: `Outer.Inner` for a nested
     * class, or its class-level `@SerialName`.
     */
    private fun serializableModelNames(): Set<String> {
        val packagePath = ApiEnvelope::class.java.`package`.name.replace('.', '/')
        val location = File(ApiEnvelope::class.java.protectionDomain.codeSource.location.toURI())
        val classFiles = if (location.isDirectory) {
            File(location, packagePath).listFiles().orEmpty().map { it.name }
        } else {
            JarFile(location).use { jar ->
                jar.entries().toList().map { it.name }
                    .filter { it.substringBeforeLast('/') == packagePath }
                    .map { it.substringAfterLast('/') }
            }
        }
        return classFiles
            .filter { it.endsWith(SERIALIZER_CLASS_SUFFIX) }
            .map { Class.forName("${ApiEnvelope::class.java.`package`.name}.${it.removeSuffix(SERIALIZER_CLASS_SUFFIX)}") }
            .map { it.getAnnotation(SerialName::class.java)?.value ?: it.canonicalName }
            .toSet()
    }

    private companion object {
        const val KOTLINX_JSON_PREFIX = "kotlinx.serialization.json."
        const val SERIALIZER_CLASS_SUFFIX = "\$\$serializer.class"

        val spec = Spec(
            Json.parseToJsonElement(
                File(checkNotNull(System.getProperty("igloo.openapi")) {
                    "igloo.openapi is unset; app/build.gradle.kts passes it to every Test task"
                }).readText(),
            ).jsonObject,
        )

        fun <T> envelope(data: KSerializer<T>) = ApiEnvelope.serializer(data)

        /**
         * Every route in `data/api`. The ones with no model are here so that the route itself is
         * checked: the body is ignored (logout, HLS stop) or is media rather than JSON.
         */
        val routes = listOf(
            Route(
                "POST",
                "/api/auth/device-login",
                envelope(DeviceTokenData.serializer()),
                DeviceLoginRequest.serializer(),
            ),
            Route("GET", "/api/auth/user", envelope(AuthUserData.serializer())),
            Route("DELETE", "/api/auth/logout"),
            Route(
                "POST",
                "/api/quick-connect/initiate",
                envelope(QuickConnectInitiateData.serializer()),
                QuickConnectInitiateRequest.serializer(),
            ),
            Route(
                "POST",
                "/api/quick-connect/redeem",
                envelope(QuickConnectRedeemData.serializer()),
                QuickConnectRedeemRequest.serializer(),
            ),
            Route(
                "POST",
                "/api/user/pin/verify",
                envelope(UserPinVerifyData.serializer()),
                VerifyUserPinRequest.serializer(),
            ),

            Route("GET", "/api/movies/latest", envelope(LatestMoviesData.serializer())),
            Route("GET", "/api/movies/library", envelope(MoviesLibraryData.serializer())),
            Route("GET", "/api/movies/genres", envelope(MovieGenresData.serializer())),
            Route("GET", "/api/movies/genres/{genreId}/movies", envelope(MoviesLibraryData.serializer())),
            Route("GET", "/api/movies/liked", envelope(MoviesLibraryData.serializer())),
            Route("GET", "/api/movies/stats", envelope(MoviesStatsData.serializer())),
            Route("GET", "/api/continue-watching", envelope(ContinueWatchingData.serializer())),
            Route("GET", "/api/movies/details/{id}", envelope(MovieDetailsData.serializer())),
            Route("GET", "/api/tmdb/movies/in-theaters", envelope(TheaterMoviesData.serializer())),
            Route("GET", "/api/tmdb/movies/{id}", envelope(TmdbMovieData.serializer())),
            Route(
                "GET",
                "/api/movies/{id}/technical-details",
                envelope(MovieTechnicalDetailsData.serializer()),
            ),
            Route("GET", "/api/movies/{id}/watch-progress", envelope(WatchProgress.serializer())),
            Route(
                "PUT",
                "/api/movies/{id}/watch-progress",
                envelope(WatchProgressUpdateData.serializer()),
                UpdateWatchProgressRequest.serializer(),
            ),
            Route(
                "PUT",
                "/api/movies/{id}/watch-progress/watched",
                envelope(MovieWatchedData.serializer()),
                SetMovieWatchedRequest.serializer(),
            ),
            Route("GET", "/api/movies/{id}/like-status", envelope(MovieLikeStatusData.serializer())),
            Route("POST", "/api/movies/{id}/like", envelope(MovieLikeToggleData.serializer())),
            Route("GET", "/api/movies/{id}/stream"),
            Route("GET", "/api/movies/{id}/hls/{profile}/playlist.m3u8"),
            Route("POST", "/api/movies/{id}/hls/session/stop"),
            Route("GET", "/api/movies/{id}/subtitles/{trackIndex}/web.vtt"),

            Route("GET", "/api/shows/library", envelope(ShowsLibraryData.serializer())),
            Route("GET", "/api/shows/genres", envelope(ShowGenresData.serializer())),
            Route("GET", "/api/shows/genres/{genreId}/shows", envelope(ShowsLibraryData.serializer())),
            Route("GET", "/api/shows/stats", envelope(ShowsStatsData.serializer())),
            Route("GET", "/api/shows/episodes/{id}", envelope(ShowEpisodePlaybackData.serializer())),
            Route(
                "GET",
                "/api/shows/episodes/{id}/technical-details",
                envelope(ShowEpisodeTechnicalDetailsData.serializer()),
            ),
            Route("GET", "/api/shows/episodes/{id}/watch-progress", envelope(WatchProgress.serializer())),
            Route(
                "PUT",
                "/api/shows/episodes/{id}/watch-progress",
                envelope(WatchProgressUpdateData.serializer()),
                UpdateWatchProgressRequest.serializer(),
            ),
            Route("GET", "/api/shows/episodes/{id}/stream"),
            Route("GET", "/api/shows/episodes/{id}/hls/{profile}/playlist.m3u8"),
            Route("POST", "/api/shows/episodes/{id}/hls/session/stop"),
            Route("GET", "/api/shows/episodes/{id}/subtitles/{trackIndex}/web.vtt"),

            Route("GET", "/api/music/albums/latest", envelope(LatestAlbumsData.serializer())),
            Route("GET", "/api/music/albums", envelope(AlbumsData.serializer())),
            Route("GET", "/api/music/albums/details/{id}", envelope(AlbumDetailsData.serializer())),
            Route("GET", "/api/music/musicians", envelope(MusiciansData.serializer())),
            Route("GET", "/api/music/musicians/{id}", envelope(MusicianDetailsData.serializer())),
            Route("GET", "/api/music/tracks", envelope(TracksData.serializer())),
            Route("GET", "/api/music/tracks/shuffle", envelope(ShuffleTracksData.serializer())),
            Route("GET", "/api/music/tracks/liked-ids", envelope(LikedTrackIdsData.serializer())),
            Route("POST", "/api/music/tracks/{id}/like", envelope(TrackLikeToggleData.serializer())),
            Route("GET", "/api/music/stats", envelope(MusicStats.serializer())),
            Route("GET", "/api/music/tracks/{id}/stream"),
        )

        /** Models decoded outside a route's success body: `safeApiCall` reads the error body. */
        val schemaRoots = mapOf("ErrorResponse" to MessageResponse.serializer())

        /**
         * `Class.wire_field` to why the model is right and the spec is not, confirmed against
         * the server in the main repository. The test fails once the spec stops disagreeing.
         */
        val knownSpecDeviations = emptyMap<String, String>()
    }
}
