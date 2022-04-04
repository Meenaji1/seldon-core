package io.seldon.dataflow

import io.grpc.ManagedChannelBuilder
import io.grpc.StatusException
import io.seldon.dataflow.kafka.KafkaProperties
import io.seldon.dataflow.kafka.Transformer
import io.seldon.dataflow.kafka.parseSource
import io.seldon.dataflow.kafka.transformerFor
import io.seldon.mlops.chainer.ChainerGrpcKt
import io.seldon.mlops.chainer.ChainerOuterClass.*
import io.seldon.mlops.chainer.ChainerOuterClass.PipelineUpdateMessage.PipelineOperation
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import io.klogging.logger as coLogger

typealias PipelineId = String

data class PipelineMetadata(
    val id: PipelineId,
    val name: String,
    val version: Int,
)

data class PipelineTopology(
    val metadata: PipelineMetadata,
    val transformers: List<Transformer>,
)

@OptIn(FlowPreview::class)
class PipelineSubscriber(
    private val name: String,
    private val kafkaProperties: KafkaProperties,
    upstreamHost: String,
    upstreamPort: Int,
    grpcServiceConfig: Map<String, Any>,
) {
    private val channel = ManagedChannelBuilder
        .forAddress(upstreamHost, upstreamPort)
        .defaultServiceConfig(grpcServiceConfig)
        .usePlaintext() // Use TLS
        .enableRetry()
        .build()
    private val client = ChainerGrpcKt.ChainerCoroutineStub(channel)
    private val pipelines = ConcurrentHashMap<PipelineId, PipelineTopology>()


    // TODO
    //  - If a topology encounters an error, we should signal back to the scheduler about this.
    //  - If the scheduler updates/removes a topology, we need to cancel the corresponding coroutine.
    //  ...
    //  Pipeline UID should be enough to uniquely key it, even across versions?
    //  ...
    //  - Add map of model name -> (weak) referrents/reference count to avoid recreation of streams
    suspend fun subscribe() {
        val retryCountMax = 10
        var retries = 0
        var retry = true
        while (retry){
            try {
                client
                    .subscribePipelineUpdates(request = makeSubscriptionRequest())
                    .onEach { update ->
                        logger.info("received request for ${update.pipeline}:${update.version} Id:${update.uid}")

                        val metadata = PipelineMetadata(
                            id = update.uid,
                            name = update.pipeline,
                            version = update.version,
                        )

                        when (update.op) {
                            PipelineOperation.Create -> handleCreate(metadata, update.updatesList)
                            PipelineOperation.Delete -> handleDelete(metadata)
                            else -> logger.warn("unrecognised pipeline operation (${update.op})")
                        }
                    }
                    .onCompletion {
                        logger.info("pipeline subscription terminated")
                    }
                    .collect()
            } catch (e: StatusException) {
                retries++
                if (retries < retryCountMax) {
                    logger.info("Got status exception. Sleeping..")
                    Thread.sleep(1_000)
                } else {
                    throw e
                }

            }
        }
        // TODO - error handling?
        // TODO - use supervisor job(s) for spawning coroutines?
    }

    private fun makeSubscriptionRequest() =
        PipelineSubscriptionRequest
            .newBuilder()
            .setName(name)
            .build()

    // Create topics if they don't exist
    private suspend fun createTopics(
        steps: List<PipelineStepUpdate>,
    )
    {
        val admin = Admin.create(kafkaProperties)
        var topicNames = steps.flatMap { step -> step.sourcesList }
        topicNames += steps.map { step -> step.sink }
        val uniqueTopicNames = topicNames.map { topicName -> parseSource(topicName).first }.toSet()
        logger.info("Topics found are ${uniqueTopicNames}")
        val newTopics = uniqueTopicNames.map { topicName ->  NewTopic(topicName, 1, 1)}
        val result = admin.createTopics(newTopics)
        result.values().forEach { result ->
            try {
                result.value.get()
            }
            catch (e: ExecutionException)
            {
                if (e.cause is TopicExistsException) {
                    logger.info("Topic already exists ${result.key}")
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun handleCreate(
        metadata: PipelineMetadata,
        steps: List<PipelineStepUpdate>,
    ) {
        val transformers = steps.mapNotNull { transformerFor(metadata.name, it.sourcesList, it.tensorMapMap, it.sink, kafkaProperties) }

        createTopics(steps)

        if (transformers.size != steps.size) {
            client.pipelineUpdateEvent(makePipelineUpdateEvent(
                metadata = metadata,
                operation = PipelineOperation.Create,
                success = false,
                reason = "failed to create all pipeline steps"
            ))
            return
        }


        val previous = pipelines
            .putIfAbsent(
                metadata.id,
                PipelineTopology(metadata, transformers),
            )

        if (previous == null) {
            transformers.forEach { it.start() }
            client.pipelineUpdateEvent(makePipelineUpdateEvent(
                metadata = metadata,
                operation = PipelineOperation.Create,
                success = true,
                reason = "Created pipeline"
            ))
        } else {
            logger.warn("not creating pipeline ${metadata.id} as it already exists")
        }
    }

    private suspend fun handleDelete(metadata: PipelineMetadata) {
        logger.info("Delete pipeline ${metadata.name}")
        pipelines
            .remove(metadata.id)
            ?.also { pipeline ->
                runBlocking {
                    pipeline.transformers
                        .asFlow()
                        .parallel(
                            scope = this,
                            concurrency = pipeline.transformers.size,
                        ) { step ->
                            cancelPipelineStep(pipeline.metadata, step, "removal requested")
                        }.collect()
                }
            }
        client.pipelineUpdateEvent(
            makePipelineUpdateEvent(
                metadata = metadata,
                operation = PipelineOperation.Delete,
                success = true,
                reason = "Pipeline removed",
            ))
    }

    fun cancelPipelines(reason: String) {
        runBlocking {
            logger.info("cancelling pipelines")
            pipelines.values
                .flatMap { pipeline ->
                    pipeline.transformers.map { pipeline.metadata to it }
                }
                .asFlow()
                .parallel(scope = this, concurrency = pipelines.size) { (metadata, transformer) ->
                    cancelPipelineStep(metadata, transformer, reason)
                }
                .collect()
        }
    }

    private suspend fun cancelPipelineStep(
        metadata: PipelineMetadata,
        transformer: Transformer,
        reason: String,
    ) {
        logger.info("cancel pipeline ${metadata.name}")
        transformer.stop()
    }

    private fun makePipelineUpdateEvent(
        metadata: PipelineMetadata,
        operation: PipelineOperation,
        success: Boolean,
        reason: String = "",
    ): PipelineUpdateStatusMessage {
        return PipelineUpdateStatusMessage
            .newBuilder()
            .setSuccess(success)
            .setReason(reason)
            .setUpdate(
                PipelineUpdateMessage
                    .newBuilder()
                    .setOp(operation)
                    .setPipeline(metadata.name)
                    .setVersion(metadata.version)
                    .setUid(metadata.id)
                    .build()
            )
            .build()
    }

    companion object {
        private val logger = coLogger(PipelineSubscriber::class)
    }
}