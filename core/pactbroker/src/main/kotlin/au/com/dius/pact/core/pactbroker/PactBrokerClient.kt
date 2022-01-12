package au.com.dius.pact.core.pactbroker

import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.Utils
import au.com.dius.pact.core.support.Utils.lookupEnvironmentValue
import au.com.dius.pact.core.support.handleWith
import au.com.dius.pact.core.support.ifNullOrEmpty
import au.com.dius.pact.core.support.isNotEmpty
import au.com.dius.pact.core.support.json.JsonParser
import au.com.dius.pact.core.support.json.JsonValue
import au.com.dius.pact.core.support.json.map
import au.com.dius.pact.core.support.jsonArray
import au.com.dius.pact.core.support.jsonObject
import au.com.dius.pact.core.support.toJson
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.unwrap
import com.google.common.net.UrlEscapers.urlPathSegmentEscaper
import mu.KLogging
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.function.Consumer

/**
 * Wraps the response for a Pact from the broker with the link data associated with the Pact document.
 */
data class PactResponse(val pactFile: JsonValue.Object, val links: Map<String, Any?>)

/**
 * Test result that is sent to the Pact broker
 */
sealed class TestResult {
  /**
   * Success result
   */
  data class Ok(val interactionIds: Set<String> = emptySet()) : TestResult() {
    constructor(interactionId: String?) : this(if (interactionId.isNullOrEmpty())
      emptySet() else setOf(interactionId))

    override fun toBoolean() = true

    override fun merge(result: TestResult) = when (result) {
      is Ok -> this.copy(interactionIds = interactionIds + result.interactionIds)
      is Failed -> result.merge(this)
    }
  }

  /**
   * Failed result
   */
  data class Failed(var results: List<Map<String, Any?>> = emptyList(), val description: String = "") : TestResult() {
    override fun toBoolean() = false

    override fun merge(result: TestResult) = when (result) {
      is Ok -> if (result.interactionIds.isEmpty()) {
        this
        } else {
          val allResults = results + result.interactionIds.map { mapOf("interactionId" to it) }
          val grouped = allResults.groupBy { it["interactionId"] }
          val filtered = grouped.mapValues { entry ->
            val interactionId = entry.key as String?
            if (entry.value.size == 1) {
              entry.value
            } else {
              entry.value.map { map -> map.filterKeys { it != "interactionId" } }
                .filter { it.isNotEmpty() }
                .map { map ->
                  if (interactionId.isNullOrEmpty()) {
                    map
                  } else {
                    map + ("interactionId" to interactionId)
                  }
                }
            }
          }
          this.copy(results = filtered.values.flatten())
        }
      is Failed -> Failed(results + result.results, when {
        description.isNotEmpty() && result.description.isNotEmpty() && description != result.description ->
          "$description, ${result.description}"
        description.isNotEmpty() -> description
        else -> result.description
      })
    }
  }

  abstract fun toBoolean(): Boolean
  abstract fun merge(result: TestResult): TestResult
}

/**
 * Represents a request for the latest pact, or the latest pact for a particular tag
 */
sealed class Latest {
  data class UseLatest(val latest: Boolean) : Latest()
  data class UseLatestTag(val latestTag: String) : Latest()
}

/**
 * Model for a CanIDeploy result
 */
data class CanIDeployResult(val ok: Boolean, val message: String, val reason: String, val unknown: Int? = null)

/**
 * Consumer version selector. See https://docs.pact.io/pact_broker/advanced_topics/selectors
 */
data class ConsumerVersionSelector(
  val tag: String? = null,
  val latest: Boolean = true,
  val consumer: String? = null,
  val fallbackTag: String? = null
) {
  fun toJson(): JsonValue {
    val obj = JsonValue.Object("latest" to Json.toJson(latest))
    if (tag.isNotEmpty()) {
      obj.add("tag", Json.toJson(tag))
    }
    if (consumer.isNotEmpty()) {
      obj.add("consumer", Json.toJson(consumer))
    }
    if (fallbackTag.isNotEmpty()) {
      obj.add("fallbackTag", Json.toJson(fallbackTag))
    }
    return obj
  }
}

/**
 * Selectors to ignore with the can-i-deploy check
 */
data class IgnoreSelector @JvmOverloads constructor(var name: String = "", var version: String? = null) {
  fun set(value: String) {
    val vals = value.split(":", limit = 2)
    if (vals.size == 2) {
      name = vals[0]
      version = vals[1]
    } else {
      name = vals[0]
    }
  }
}

/**
 * Interface to a Pact Broker client
 */
interface IPactBrokerClient {
  /**
   * Fetches all consumers for the given provider and selectors
   */
  @Throws(IOException::class)
  fun fetchConsumersWithSelectors(
    providerName: String,
    selectors: List<ConsumerVersionSelector>,
    providerTags: List<String> = emptyList(),
    enablePending: Boolean = false,
    includeWipPactsSince: String?
  ): Result<List<PactBrokerResult>, Exception>

  fun getUrlForProvider(providerName: String, tag: String): String?

  val options: Map<String, Any>

  /**
   * Publish all the tags for the provider to the Pact broker
   * @param docAttributes Attributes associated with the fetched Pact file
   * @param name Provider name
   * @param tags Provider tags to tag the provider with
   * @param version Provider version
   */
  fun publishProviderTags(
    docAttributes: Map<String, Any?>,
    name: String,
    tags: List<String>,
    version: String
  ): Result<Boolean, List<String>>

  /**
   * Publish provider branch to the Pact broker
   * @param docAttributes Attributes associated with the fetched Pact file
   * @param name Provider name
   * @param branch Provider branch
   * @param version Provider version
   */
  fun publishProviderBranch(
    docAttributes: Map<String, Any?>,
    name: String,
    branch: String,
    version: String
  ): Result<Boolean, String>

  /**
   * Publishes the result to the "pb:publish-verification-results" link in the document attributes.
   */
  fun publishVerificationResults(
    docAttributes: Map<String, Any?>,
    result: TestResult,
    version: String,
    buildUrl: String?
  ): Result<Boolean, String>

  /**
   * Publishes the result to the "pb:publish-verification-results" link in the document attributes.
   */
  fun publishVerificationResults(
    docAttributes: Map<String, Any?>,
    result: TestResult,
    version: String
  ): Result<Boolean, String>

  /**
   * Uploads the given pact file to the broker and applies any tags
   */
  @Deprecated("Replaced with version that takes a configuration object")
  fun uploadPactFile(pactFile: File, version: String): Result<String?, Exception>

  /**
   * Uploads the given pact file to the broker and applies any tags
   */
  @Deprecated("Replaced with version that takes a configuration object")
  fun uploadPactFile(pactFile: File, version: String, tags: List<String>): Result<String?, Exception>

  /**
   * Uploads the given pact file to the broker and applies any tags/branches
   */
  fun uploadPactFile(pactFile: File, config: PublishConfiguration): Result<String?, Exception>
}

/**
 * Client configuration.
 */
data class PactBrokerClientConfig @JvmOverloads constructor(
  val retryCountWhileUnknown: Int = 0,
  val retryWhileUnknownInterval: Int = 10,
  val insecureTLS: Boolean = false
)

/**
 * Client for the pact broker service
 */
open class PactBrokerClient(
  val pactBrokerUrl: String,
  @Deprecated("Move use of options to PactBrokerClientConfig")
  override val options: MutableMap<String, Any>,
  val config: PactBrokerClientConfig
) : IPactBrokerClient {

  @Deprecated("Use the version that takes PactBrokerClientConfig")
  constructor(pactBrokerUrl: String) : this(pactBrokerUrl, mutableMapOf(), PactBrokerClientConfig())

  /**
   * Fetches all consumers for the given provider
   */
  @Deprecated(message = "Use the version that takes selectors instead",
    replaceWith = ReplaceWith("fetchConsumersWithSelectors"))
  open fun fetchConsumers(provider: String): List<PactBrokerResult> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerResult>()
      halClient.navigate(mapOf("provider" to provider), LATEST_PROVIDER_PACTS).forAll(PACTS, Consumer { pact ->
        val href = pact["href"].toString()
        val name = pact["name"].toString()
        consumers.add(PactBrokerResult(name, href, pactBrokerUrl))
      })
      consumers
    } catch (e: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  /**
   * Fetches all consumers for the given provider and tag
   */
  @Deprecated(message = "Use fetchConsumersWithSelectors")
  open fun fetchConsumersWithTag(provider: String, tag: String): List<PactBrokerResult> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerResult>()
      halClient.navigate(mapOf("provider" to provider, "tag" to tag), LATEST_PROVIDER_PACTS_WITH_TAG)
        .forAll(PACTS, Consumer { pact ->
        val href = pact["href"].toString()
        val name = pact["name"].toString()
        consumers.add(PactBrokerResult(name, href, pactBrokerUrl, emptyList(), tag = tag))
      })
      consumers
    } catch (e: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  override fun fetchConsumersWithSelectors(
    providerName: String,
    selectors: List<ConsumerVersionSelector>,
    providerTags: List<String>,
    enablePending: Boolean,
    includeWipPactsSince: String?
  ): Result<List<PactBrokerResult>, Exception> {
    val halClient = when (val navigateResult = handleWith<IHalClient> { newHalClient().navigate() }) {
      is Err<Exception> -> return navigateResult
      is Ok<IHalClient> -> navigateResult.value
    }
    val pactsForVerification = when {
      halClient.linkUrl(PROVIDER_PACTS_FOR_VERIFICATION) != null -> PROVIDER_PACTS_FOR_VERIFICATION
      halClient.linkUrl(BETA_PROVIDER_PACTS_FOR_VERIFICATION) != null -> BETA_PROVIDER_PACTS_FOR_VERIFICATION
      else -> null
    }
    return if (pactsForVerification != null) {
      val selectorsRawJson = System.getProperty("pactbroker.consumerversionselectors.rawjson")
      if(!selectorsRawJson.isNullOrBlank()){
        fetchPactsUsingNewEndpointRaw(selectorsRawJson, enablePending, providerTags, includeWipPactsSince, halClient, pactsForVerification, providerName)
      } else {
        fetchPactsUsingNewEndpointTyped(selectors, enablePending, providerTags, includeWipPactsSince, halClient, pactsForVerification, providerName)
      }
    } else {
      handleWith {
        val tags = selectors.filter { it.tag.isNotEmpty() }.map { it.tag to it.fallbackTag }
        if (tags.isEmpty()) {
          fetchConsumers(providerName)
        } else {
          tags.flatMap { (tag, fallbacktag) ->
            val tagResult = fetchConsumersWithTag(providerName, tag!!)
            if (tagResult.isEmpty() && fallbacktag != null) {
              fetchConsumersWithTag(providerName, fallbacktag)
            } else {
              tagResult
            }
          }
        }
      }
    }
  }

  private fun fetchPactsUsingNewEndpointTyped(
    selectorsTyped: List<ConsumerVersionSelector>,
    enablePending: Boolean,
    providerTags: List<String>,
    includeWipPactsSince: String?,
    halClient: IHalClient,
    pactsForVerification: String,
    providerName: String
  ): Result<List<PactBrokerResult>, Exception> {
    val selectorsJson = jsonArray(selectorsTyped.map { it.toJson() })
    return fetchPactsUsingNewEndpoint(selectorsJson, enablePending, providerTags, includeWipPactsSince, halClient, pactsForVerification, providerName)
  }

  private fun fetchPactsUsingNewEndpointRaw(
    selectorsRaw: String,
    enablePending: Boolean,
    providerTags: List<String>,
    includeWipPactsSince: String?,
    halClient: IHalClient,
    pactsForVerification: String,
    providerName: String
  ): Result<List<PactBrokerResult>, Exception> {
    return fetchPactsUsingNewEndpoint(JsonParser.parseString(selectorsRaw), enablePending, providerTags, includeWipPactsSince, halClient, pactsForVerification, providerName)
  }

  private fun fetchPactsUsingNewEndpoint(
    selectorsJson: JsonValue,
    enablePending: Boolean,
    providerTags: List<String>,
    includeWipPactsSince: String?,
    halClient: IHalClient,
    pactsForVerification: String,
    providerName: String
  ): Result<List<PactBrokerResult>, Exception> {
    logger.debug { "Fetching pacts using the pactsForVerification endpoint" }
    val body = JsonValue.Object(
      "consumerVersionSelectors" to selectorsJson
    )

    body["includePendingStatus"] = enablePending
    if (enablePending) {
      body["providerVersionTags"] = jsonArray(providerTags)
      if (includeWipPactsSince.isNotEmpty()) {
        body["includeWipPactsSince"] = includeWipPactsSince
      }
    }

    return handleWith {
      halClient.postJson(pactsForVerification, mapOf("provider" to providerName), body.serialise()).map { result ->
        result["_embedded"]["pacts"].asArray().map { pactJson ->
          val selfLink = pactJson["_links"]["self"]
          val href = Json.toString(selfLink["href"])
          val name = Json.toString(selfLink["name"])
          val properties = pactJson["verificationProperties"]
          val notices = properties["notices"].asArray()?.map { VerificationNotice.fromJson(it) }?.filterNotNull() ?:
            emptyList()
          var pending = false
          if (properties is JsonValue.Object && properties.has("pending") && properties["pending"].isBoolean) {
            pending = properties["pending"].asBoolean()!!
          }
          val wip = if (properties.has("wip") && properties["wip"].isBoolean)
            properties["wip"].asBoolean()!!
          else false

          PactBrokerResult(name, href, pactBrokerUrl, emptyList(), notices, pending, wip = wip,
              usedNewEndpoint = true)
        }
      }
    }
  }

  /**
   * Uploads the given pact file to the broker, and optionally applies any tags
   */
  override fun uploadPactFile(pactFile: File, version: String) = uploadPactFile(pactFile, version, emptyList())

  /**
   * Uploads the given pact file to the broker, and optionally applies any tags
   */
  override fun uploadPactFile(pactFile: File, version: String, tags: List<String>) =
    uploadPactFile(pactFile, PublishConfiguration(version, tags))

  override fun uploadPactFile(pactFile: File, config: PublishConfiguration): Result<String?, Exception> {
    val pactText = pactFile.readText()
    val pact = JsonParser.parseString(pactText)
    val halClient = newHalClient().navigate()
    val providerName = Json.toString(pact["provider"]["name"])
    val consumerName = Json.toString(pact["consumer"]["name"])

    val publishContractsLink = halClient.linkUrl(PUBLISH_CONTRACTS_LINK)
    return if (publishContractsLink != null) {
      when (val result = publishContract(halClient, providerName, consumerName, config, pactText)) {
        is Ok -> Ok("OK")
        is Err -> result
      }
    } else {
      if (config.tags.isNotEmpty()) {
        uploadTags(halClient, consumerName, config.consumerVersion, config.tags)
      }
      halClient.putJson(
        "pb:publish-pact", mapOf(
          "provider" to providerName,
          "consumer" to consumerName,
          "consumerApplicationVersion" to config.consumerVersion
        ), pactText
      )
    }
  }

  /**
   * Publish the contract using the "Publish Contracts" endpoint
   */
  fun publishContract(
    halClient: IHalClient,
    providerName: String,
    consumerName: String,
    config: PublishConfiguration,
    pactText: String
  ): Result<JsonValue.Object, Exception> {
    val branchName = branchName(config)
    val consumerBuildUrl = consumerBuildUrl(config)
    val bodyValues = mutableMapOf(
      "pacticipantName" to consumerName.toJson(),
      "pacticipantVersionNumber" to consumerVersion(config),
      "tags" to JsonValue.Array(config.tags.map { it.toJson() }.toMutableList()),
      "contracts" to JsonValue.Array(
        mutableListOf(
          JsonValue.Object(
            "consumerName" to consumerName.toJson(),
            "providerName" to providerName.toJson(),
            "specification" to "pact".toJson(),
            "contentType" to "application/json".toJson(),
            "content" to Base64.getEncoder().encodeToString(pactText.toByteArray()).toJson()
          )
        )
      )
    )
    if (branchName != JsonValue.Null) {
      bodyValues["branch"] = branchName
    }
    if (consumerBuildUrl != JsonValue.Null) {
      bodyValues["buildUrl"] = consumerBuildUrl
    }

    val body = JsonValue.Object(bodyValues)
    return when (val result = halClient.postJson(PUBLISH_CONTRACTS_LINK, mapOf(), body.serialise())) {
      is Ok -> {
        displayNotices(result.value)
        result
      }
      is Err -> {
        val error = result.error
        if (error is RequestFailedException && error.body != null) {
          when (val json = handleWith<JsonValue> { JsonParser.parseString(error.body) }) {
            is Ok -> if (json.value is JsonValue.Object) {
              val body: JsonValue.Object = json.value.downcast()
              displayNotices(body)
              if (error.status.statusCode == 400) {
                displayErrors(body)
              }
            } else {
              logger.error { "Response from Pact Broker was not in correct JSON format: got ${json.value}" }
            }
            is Err -> {
              logger.error { "Response from Pact Broker was not in JSON format: ${json.error}" }
            }
          }
        }
        result
      }
    }
  }

  private fun consumerBuildUrl(config: PublishConfiguration): JsonValue {
    return config.consumerBuildUrl.ifNullOrEmpty {
      lookupEnvironmentValue("pact.publish.consumer.buildUrl")
    }.toJson()
  }

  private fun branchName(config: PublishConfiguration): JsonValue {
    return config.branchName.ifNullOrEmpty {
      lookupEnvironmentValue("pact.publish.consumer.branchName")
    }.toJson()
  }

  private fun consumerVersion(config: PublishConfiguration): JsonValue {
    return config.consumerVersion.ifNullOrEmpty {
      lookupEnvironmentValue("pact.publish.consumer.version")
    }.toJson()
  }

  private fun displayNotices(result: JsonValue.Object) {
    val notices = result["notices"]
    if (notices is JsonValue.Array) {
      for (noticeJson in notices.values) {
        if (noticeJson.isObject) {
          val notice: JsonValue.Object = noticeJson.downcast()
          val level = notice["level"].asString()
          val text = notice["text"].asString()
          when (level) {
            "info", "prompt" -> logger.info { "notice: $text" }
            "warning", "danger" -> logger.warn { "notice: $text" }
            "error" -> logger.error { "notice: $text" }
            else -> logger.debug { "notice: $text" }
          }
        } else {
          logger.error("Got an invalid notice value from the Pact Broker: Expected an object, got ${notices.name}")
        }
      }
    } else {
      logger.error("Got an invalid notices value from the Pact Broker: Expected an array, got ${notices.name}")
    }
  }

  private fun displayErrors(result: JsonValue.Object) {
    val errors = result["errors"]
    if (errors is JsonValue.Object) {
      for ((key, errorJson) in errors.entries) {
        if (errorJson.isArray) {
          for (error in errorJson.asArray()!!.values) {
            logger.error("$key: $error")
          }
        } else {
          logger.error("$key: $errorJson")
        }
      }
    }
  }

  override fun getUrlForProvider(providerName: String, tag: String): String? {
    val halClient = newHalClient()
    if (tag.isEmpty() || tag == "latest") {
      halClient.navigate(mapOf("provider" to providerName), LATEST_PROVIDER_PACTS)
    } else {
      halClient.navigate(mapOf("provider" to providerName, "tag" to tag), LATEST_PROVIDER_PACTS_WITH_TAG)
    }
    return halClient.linkUrl(PACTS)
  }

  open fun fetchPact(url: String, encodePath: Boolean = true): PactResponse {
    val halDoc = newHalClient().fetch(url, encodePath).unwrap()
    return PactResponse(halDoc, HalClient.asMap(halDoc["_links"].asObject()))
  }

  open fun newHalClient(): IHalClient = HalClient(pactBrokerUrl, options, config)

  override fun publishVerificationResults(
    docAttributes: Map<String, Any?>,
    result: TestResult,
    version: String
  ) = publishVerificationResults(docAttributes, result, version, null)

  override fun publishVerificationResults(
    docAttributes: Map<String, Any?>,
    result: TestResult,
    version: String,
    buildUrl: String?
  ): Result<Boolean, String> {
    val halClient = newHalClient()
    val publishLink = docAttributes.mapKeys { it.key.toLowerCase() } ["pb:publish-verification-results"] // ktlint-disable curly-spacing
    return if (publishLink is Map<*, *>) {
      val jsonObject = buildPayload(result, version, buildUrl)
      val lowercaseMap = publishLink.mapKeys { it.key.toString().toLowerCase() }
      if (lowercaseMap.containsKey("href")) {
        halClient.postJson(lowercaseMap["href"].toString(), jsonObject.serialise()).mapError {
          logger.error(it) { "Publishing verification results failed with an exception" }
          "Publishing verification results failed with an exception: ${it.message}"
        }
      } else {
        Err("Unable to publish verification results as there is no pb:publish-verification-results link")
      }
    } else {
      Err("Unable to publish verification results as there is no pb:publish-verification-results link")
    }
  }

  fun buildPayload(result: TestResult, version: String, buildUrl: String?): JsonValue.Object {
    val jsonObject = jsonObject("success" to result.toBoolean(),
      "providerApplicationVersion" to version,
      "verifiedBy" to mapOf(
        "implementation" to "Pact-JVM", "version" to Utils.lookupVersion(PactBrokerClient::class.java)
      )
    )
    if (buildUrl != null) {
      jsonObject["buildUrl"] = buildUrl
    }

    logger.debug { "Test result = $result" }
    if (result is TestResult.Failed && result.results.isNotEmpty()) {
      val values = result.results
        .groupBy { it["interactionId"] }
        .map { mismatches ->
          val values = mismatches.value
            .filter { !it.containsKey("exception") }
            .map { mismatch ->
              val remainingAttributes = mismatch.filterNot { it.key == "interactionId" }
              when (mismatch["attribute"]) {
                "body-content-type" -> listOf("attribute" to "body", "description" to mismatch["description"])
                else -> remainingAttributes.map { it.toPair() }
              }
            }.filter { it.isNotEmpty() }
            .map { jsonObject(it) }

          val exceptionDetails = mismatches.value.find { it.containsKey("exception") }
          val exceptions = if (exceptionDetails != null) {
            val exception = exceptionDetails["exception"]
            val description = exceptionDetails["description"]
            if (exception is Throwable) {
              if (description != null) {
                jsonArray(jsonObject("message" to description.toString() + ": " + exception.message,
                  "exceptionClass" to exception.javaClass.name))
              } else {
                jsonArray(jsonObject("message" to exception.message,
                  "exceptionClass" to exception.javaClass.name))
              }
            } else {
              jsonArray(jsonObject("message" to exception.toString()))
            }
          } else {
            null
          }

          val interactionJson = if (values.isEmpty() && exceptions == null) {
            jsonObject("interactionId" to mismatches.key, "success" to true)
          } else {
            val json = jsonObject(
              "interactionId" to mismatches.key, "success" to false,
              "mismatches" to jsonArray(values)
            )
            if (exceptions != null) {
              json["exceptions"] = exceptions
            }
            json
          }
          interactionJson
        }
      jsonObject["testResults"] = jsonArray(values)
    }
    return jsonObject
  }

  /**
   * Fetches the consumers of the provider that have no associated tag
   */
  @Deprecated(message = "Use the version that takes selectors instead",
    replaceWith = ReplaceWith("fetchConsumersWithSelectors"))
  open fun fetchLatestConsumersWithNoTag(provider: String): List<PactBrokerResult> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerResult>()
      halClient.navigate(mapOf("provider" to provider), LATEST_PROVIDER_PACTS_WITH_NO_TAG)
        .forAll(PACTS, Consumer { pact ->
          val href = URLDecoder.decode(pact["href"].toString(), UTF8)
          val name = pact["name"].toString()
          if (options.containsKey("authentication")) {
            consumers.add(PactBrokerResult(name, href, pactBrokerUrl, options["authentication"] as List<String>))
          } else {
            consumers.add(PactBrokerResult(name, href, pactBrokerUrl, emptyList()))
          }
        })
      consumers
    } catch (_: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  @Deprecated("Use publishProviderTags", replaceWith = ReplaceWith("publishProviderTags"))
  fun publishProviderTag(docAttributes: Map<String, Any?>, name: String, tag: String, version: String) {
    try {
      val halClient = newHalClient()
        .withDocContext(docAttributes)
        .navigate(PROVIDER)
      logPublishingResults(halClient, version, tag, name)
    } catch (e: NotFoundHalResponse) {
      logger.error(e) { "Could not tag provider $name, link was missing" }
    }
  }

  private fun logPublishingResults(halClient: IHalClient, version: String, tag: String, name: String) {
    when (val result = halClient.putJson(PROVIDER_TAG_VERSION, mapOf("version" to version, "tag" to tag), "{}")) {
      is Ok<*> -> logger.debug { "Pushed tag $tag for provider $name and version $version" }
      is Err<Exception> -> logger.error(result.error) { "Failed to push tag $tag for provider $name and version $version" }
    }
  }

  override fun publishProviderTags(
    docAttributes: Map<String, Any?>,
    name: String,
    tags: List<String>,
    version: String
  ): Result<Boolean, List<String>> {
    try {
      val halClient = newHalClient()
        .withDocContext(docAttributes)
        .navigate(PROVIDER)
      val initial: Result<Boolean, List<String>> = Ok(true)
      return tags.map { tagName ->
        val result = halClient.putJson(PROVIDER_TAG_VERSION, mapOf("version" to version, "tag" to tagName), "{}")
        when (result) {
          is Ok<*> -> logger.debug { "Pushed tag $tagName for provider $name and version $version" }
          is Err<Exception> -> logger.error(result.error) { "Failed to push tag $tagName for provider $name and version $version" }
        }
        result.mapError { err -> "Publishing tag '$tagName' failed: ${err.message ?: err.toString()}" }
      }.fold(initial) { result, v ->
        when {
          result is Ok && v is Ok -> result
          result is Ok && v is Err -> Err(listOf(v.error))
          result is Err && v is Ok -> result
          result is Err && v is Err -> Err(result.error + v.error)
          else -> result
        }
      }
    } catch (e: NotFoundHalResponse) {
      logger.error(e) { "Could not tag provider $name, link was missing" }
      return Err(listOf("Could not tag provider $name, link was missing"))
    }
  }

  override fun publishProviderBranch(
    docAttributes: Map<String, Any?>,
    name: String,
    branch: String,
    version: String
  ): Result<Boolean, String> {
    try {
      val halClient = newHalClient()
        .withDocContext(docAttributes)
        .navigate(PROVIDER)
      val result = halClient.putJson(PROVIDER_BRANCH_VERSION,
        mapOf("version" to version, "branch" to branch), "{}")
      return when (result) {
          is Ok<*> -> {
            logger.debug { "Pushed branch $branch for provider $name and version $version" }
            Ok(true)
          }
          is Err<Exception> -> {
            logger.error(result.error) { "Failed to push branch $branch for provider $name and version $version" }
            Err("Publishing branch '$branch' failed: ${result.error.message ?: result.error.toString()}")
          }
      }

    } catch (e: NotFoundHalResponse) {
      val message = "Could not create branch for provider $name, link was missing. It looks like your Pact Broker " +
        "does not support branches, please update to Pact Broker version 2.86.0 or later for branch support"
      logger.error(e) { message }
      return Err(message)
    }
  }

  @JvmOverloads
  open fun canIDeploy(
    pacticipant: String,
    pacticipantVersion: String,
    latest: Latest,
    to: String?,
    ignore: List<IgnoreSelector> = emptyList()
  ): CanIDeployResult {
    val halClient = newHalClient()
    val path = "/matrix?" + internalBuildMatrixQuery(pacticipant, pacticipantVersion, latest, to, ignore)
    logger.debug { "Matrix Query: $path" }
    return retryWith(
      "canIDeploy: Retrying request as there are unknown results",
      config.retryCountWhileUnknown,
      config.retryWhileUnknownInterval,
      { result -> !result.ok && result.unknown != null && result.unknown > 0 }
    ) {
      when (val result = halClient.getJson(path, false)) {
        is Ok<JsonValue> -> {
          val summary: JsonValue.Object = result.value["summary"].downcast()
          CanIDeployResult(Json.toBoolean(summary["deployable"]), "", Json.toString(summary["reason"]),
            Json.toInteger(summary["unknown"]))
        }
        is Err<Exception> -> {
          logger.error(result.error) { "Pact broker matrix query failed: ${result.error.message}" }
          CanIDeployResult(false, result.error.message.toString(), "")
        }
      }
    }
  }

  open fun createVersionTag(
    pacticipant: String,
    pacticipantVersion: String,
    tag: String
  ) =
      uploadTags(
          newHalClient(),
          pacticipant,
          pacticipantVersion,
          listOf(tag)
      )

  companion object : KLogging() {
    const val LATEST_PROVIDER_PACTS_WITH_NO_TAG = "pb:latest-untagged-pact-version"
    const val LATEST_PROVIDER_PACTS = "pb:latest-provider-pacts"
    const val LATEST_PROVIDER_PACTS_WITH_TAG = "pb:latest-provider-pacts-with-tag"
    const val PROVIDER_PACTS_FOR_VERIFICATION = "pb:provider-pacts-for-verification"
    const val BETA_PROVIDER_PACTS_FOR_VERIFICATION = "beta:provider-pacts-for-verification"
    const val PROVIDER = "pb:provider"
    const val PROVIDER_TAG_VERSION = "pb:version-tag"
    const val PROVIDER_BRANCH_VERSION = "pb:branch-version"
    const val PACTS = "pb:pacts"
    const val UTF8 = "UTF-8"
    const val PUBLISH_CONTRACTS_LINK = "pb:publish-contracts"

    fun uploadTags(
      halClient: IHalClient,
      consumerName: String,
      version: String,
      tags: List<String>
    ): Result<String?, Exception> {
      halClient.navigate()
      var result = Ok("") as Result<String?, Exception>
      tags.forEach {
        result = uploadTag(halClient, consumerName, version, it)
      }
      return result
    }

    private fun uploadTag(
      halClient: IHalClient,
      consumerName: String,
      version: String,
      it: String
    ): Result<String?, Exception> {
      val result = halClient.putJson("pb:pacticipant-version-tag", mapOf(
          "pacticipant" to consumerName,
          "version" to version,
          "tag" to it
      ), "{}")

      if (result is Err<Exception>) {
        logger.error(result.error) { "Failed to push tag $it for consumer $consumerName and version $version" }
      }

      return result
    }

    fun <T> retryWith(
      message: String,
      count: Int,
      interval: Int,
      predicate: (T) -> Boolean,
      function: () -> T
    ): T {
      var counter = 0
      var result = function()
      while (counter < count && predicate(result)) {
        counter += 1
        logger.info { "$message [$counter/$count]" }
        Thread.sleep((interval * 1000).toLong())
        result = function()
      }
      return result
    }

    /**
     * Internal: Public for testing
     */
    @JvmStatic
    fun internalBuildMatrixQuery(
      pacticipant: String,
      pacticipantVersion: String,
      latest: Latest,
      to: String?,
      ignore: List<IgnoreSelector>
    ): String {
      val escaper = urlPathSegmentEscaper()
      var params = mutableListOf("q[][pacticipant]" to escaper.escape(pacticipant), "latestby" to "cvp")

      when (latest) {
        is Latest.UseLatest -> if (latest.latest) {
          params.add("q[][latest]" to "true")
        } else {
          params.add("q[][version]" to escaper.escape(pacticipantVersion))
        }
        is Latest.UseLatestTag -> params.add("q[][tag]" to escaper.escape(latest.latestTag))
      }

      if (to.isNotEmpty()) {
        params.add("latest" to "true")
        params.add("tag" to escaper.escape(to))
      } else {
        params.add("latest" to "true")
      }

      if (ignore.isNotEmpty()) {
        for ((key, value) in ignore) {
          if (key.isNotEmpty()) {
            params.add("ignore[][pacticipant]" to key)
            if (value.isNotEmpty()) {
              params.add("ignore[][version]" to value)
            }
          }
        }
      }

      return params.joinToString("&") { "${it.first}=${it.second}" }
    }
  }
}
