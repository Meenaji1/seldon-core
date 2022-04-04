package io.seldon.dataflow.kafka

import java.util.*
import java.math.BigInteger
import java.security.MessageDigest

typealias KafkaProperties = Properties
typealias TopicName = String
typealias TensorName = String
typealias RequestId = String
typealias TRecord = ByteArray
typealias TopicsAndTensors = Pair<Set<TopicName>, Set<TensorName>>

interface Transformer {
    fun start()
    fun stop()
}

data class TopicTensors(
    val topicName: TopicName,
    val tensors: Set<TensorName>,
)

fun transformerFor(
    pipelineName: String,
    sources: List<TopicName>,
    tensorMap: kotlin.collections.Map<TensorName,TensorName>,
    sink: TopicName,
    baseKafkaProperties: KafkaProperties,
): Transformer? {
    return when (val result = parseSources(sources)) {
        is SourceProjection.Empty -> null
        is SourceProjection.Single -> Chainer(
            baseKafkaProperties.withAppId(nameFor(sources, sink, "chainer")),
            result.topicName,
            sink,
            null,
            pipelineName,
            tensorMap
        )
        is SourceProjection.SingleSubset -> Chainer(
            baseKafkaProperties.withAppId(nameFor(sources, sink, "chainer")),
            result.topicName,
            sink,
            result.tensors,
            pipelineName,
            tensorMap
        )
        is SourceProjection.Many -> Joiner(
            baseKafkaProperties.withAppId(nameFor(sources, sink, "joiner")),
            result.topicNames,
            sink,
            null,
            pipelineName,
            tensorMap
        )
        is SourceProjection.ManySubsets -> Joiner(
            baseKafkaProperties.withAppId(nameFor(sources, sink, "joiner")),
            result.topicNames,
            sink,
            result.tensorMap,
            pipelineName,
            tensorMap
        )
    }
}

fun List<TopicName>.areTensorsFromSameTopic(): Pair<Boolean, TopicsAndTensors> {
    val (topics, tensors) = this
        .map { parseSource(it) }
        .unzip()
        .run { first.toSet() to second.filterNotNull().toSet() }

    if (tensors.isEmpty() || topics.size > 1) return false to (topics to emptySet())

    return true to (topics to tensors)
}

sealed class SourceProjection {
    object Empty : SourceProjection()
    data class Single(val topicName: TopicName) : SourceProjection()
    data class SingleSubset(val topicName: TopicName, val tensors: Set<TensorName>): SourceProjection()
    data class Many(val topicNames: Set<TopicName>): SourceProjection()
    data class ManySubsets(val topicNames: Set<TensorName>, val tensorMap: Map<TopicName,Set<TensorName>>): SourceProjection()
}

fun parseSources(sources: List<TopicName>): SourceProjection {
    val topicsAndTensors = sources
        .map { parseSource(it) }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { it.value.filterNotNull().toSet() }
        .map { TopicTensors(it.key, it.value) }

    return when {
        topicsAndTensors.isEmpty() -> SourceProjection.Empty
        topicsAndTensors.size == 1 && topicsAndTensors.first().tensors.isEmpty() ->
            SourceProjection.Single(topicsAndTensors.first().topicName)
        topicsAndTensors.size == 1 ->
            SourceProjection.SingleSubset(
                topicsAndTensors.first().topicName,
                topicsAndTensors.first().tensors,
            )
        topicsAndTensors.all { it.tensors.isEmpty() } ->
            SourceProjection.Many(topicsAndTensors.map { it.topicName }.toSet())
        else ->
            SourceProjection.ManySubsets(topicsAndTensors.map { it.topicName }.toSet(), topicsAndTensors.map { it.topicName to it.tensors }.toMap())
    }
}

fun parseSource(source: TopicName): Pair<TopicName, TensorName?> {
    return when (val last = source.substringAfterLast(".", "")) {
        "" -> return last to null
        "inputs", "outputs" -> source to null
        else -> source.substringBeforeLast(".") to last
    }
}

fun md5(input:String): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
}
fun nameFor(sources: List<TopicName>, sink: TopicName, type: String): String {
    return md5("$type:${sources.joinToString(":")}:$sink")
}

object SeldonHeaders {
    const val pipelineName = "pipeline"
}