package no.nav.integrasjon

import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import java.util.*
import kotlin.reflect.full.starProjectedType

data class KafkaClientDetails(
        val baseProps: Properties,
        val topic: String,
        val pollTimeout: Long = 10_000L //for consumer
)

sealed class CommitAction
object DoCommit : CommitAction()
object NoCommit : CommitAction()

class KafkaTopicConsumer<K, out V>(private val clientDetails: KafkaClientDetails) {

    fun consumeAsync(
            kafkaEvents: SendChannel<V>,
            commitAction: ReceiveChannel<CommitAction>,
            status: SendChannel<Status>) = async {
        try {
            KafkaConsumer<K, V>(clientDetails.baseProps)
                    .apply {
                        // be a loner - independent of group logic by reading from all partitions for topic
                        assign(partitionsFor(clientDetails.topic).map { TopicPartition(it.topic(),it.partition()) })
                    }
                    .use { c ->

                        var allGood = true
                        status.send(Ready)

                        log.info("@start of consumeAsync")

                        while (isActive && allGood) {

                            c.poll(clientDetails.pollTimeout).forEach { e ->

                                log.debug {"Polled event from kafka topic ${clientDetails.topic}" }

                                // send event further down the pipeline

                                kafkaEvents.send(e.value())
                                log.debug { "Sent event to pipeline - $e" }

                                // wait for feedback from pipeline
                                when (commitAction.receive()) {
                                    DoCommit -> try {
                                        c.commitSync()
                                        log.debug { "Got DoCommit from pipeline - event committed" }
                                    }
                                    catch (e: CommitFailedException) {
                                        log.error("CommitFailedException", e)
                                        allGood = false
                                    }
                                    NoCommit -> {
                                        // problems further down the pipeline
                                        allGood = false
                                        log.debug { "Got NoCommit from pipeline - time to leave" }
                                    }
                                }
                            }
                        }
                    }
        }
        catch (e: Exception) {
            when (e) {
                is CancellationException -> {/* it's ok*/ }
                else -> log.error("Exception", e)
            }
        }
        // IllegalArgumentException, IllegalStateException, InvalidOffsetException, WakeupException
        // InterruptException, AuthenticationException, AuthorizationException, KafkaException
        // IllegalArgumentException, IllegalStateException

        // notify manager if this job is still active
        if (isActive && !status.isClosedForSend) {
            status.send(Problem)
            log.error("Reported problem to manager")
        }
        log.info("@end of consumeAsync - goodbye!")
    }

    companion object {

        private val log = KotlinLogging.logger {  }

        inline fun <reified K, reified V> init(clientDetails: KafkaClientDetails) = KafkaTopicConsumer<K,V>(
                        KafkaClientDetails(
                                consumerInjection<K,V>(clientDetails.baseProps),
                                clientDetails.topic,
                                clientDetails.pollTimeout
                        ))

        inline fun <reified K, reified V> consumerInjection(baseProps: Properties) = baseProps.apply {
            set(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, getKafkaDeserializer(K::class.starProjectedType))
            set(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, getKafkaDeserializer(V::class.starProjectedType))

            // want to be a loner for topic / not using group id - see assignment to partitions for the topic
            //set(ConsumerConfig.GROUP_ID_CONFIG, "")
            set(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            // only commit after successful put to JMS
            set(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            // poll only one record of
            set(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
        }
    }
}
