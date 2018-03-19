package no.nav.integrasjon.jms

import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import mu.KotlinLogging
import no.nav.integrasjon.manager.Problem
import no.nav.integrasjon.manager.Ready
import no.nav.integrasjon.manager.Status
import javax.jms.*
import kotlin.IllegalStateException

abstract class JMSTextMessageWriter<in V>(private val jmsDetails: JMSDetails) {

    private val connection = jmsDetails.connFactory.createConnection(jmsDetails.username,jmsDetails.password)
            .apply { this.start() }

    protected val session = connection?.createSession(false, Session.AUTO_ACKNOWLEDGE) ?:
            throw IllegalStateException("Cannot create session in JMSTextMessageWriter!")

    private val producer = session.createProducer(session.createQueue(jmsDetails.queueName))

    data class Result(val status: Boolean = false, val txtMsg: TextMessage)

    fun writeAsync(
            fromUpstream: ReceiveChannel<V>,
            toUpstream: SendChannel<Status>,
            toManager: SendChannel<Status>) = async {

        try {
            connection.use { c ->

                var allGood = true
                toManager.send(Ready)

                log.info("@start of writeAsync")

                // receive fromUpstream, send to jms, and tell pipeline to commit
                while (isActive && allGood) {

                    fromUpstream.receive().also { e ->
                        try {
                            log.info { "Received event from upstream" }
                            log.debug {"Received event: ${e.toString()}" }

                            log.info { "Invoke transformation" }
                            val result = transform(e)

                            when(result.status) {
                                true -> {
                                    log.info { "Transformation to JMS TextMessage ok" }
                                    log.debug {"Transformation ok: ${result.txtMsg.text}" }

                                    log.info { "Send TextMessage to JMS backend ${jmsDetails.queueName}" }
                                    producer.send(result.txtMsg)

                                    log.info { "Send to JMS completed" }

                                    log.info {"Send Ready to upstream"}
                                    toUpstream.send(Ready)

                                }
                                else -> {
                                    log.error {"Transformation failure, indicate problem to upstream and " +
                                            "prepare for shutdown" }
                                    allGood = false
                                    toUpstream.send(Problem)
                                }
                            }
                        }
                        catch (e: Exception) {
                            // MessageFormatException, UnsupportedOperationException
                            // InvalidDestinationException, JMSException
                            log.error("Exception", e)
                            log.error("Send Problem to upstream and prepare for shutdown")
                            allGood = false
                            toUpstream.send(Problem)
                        }
                    }
                }
            }
        }
        catch (e: Exception) {
            when(e) {
                is CancellationException -> {/* it's ok*/}
                else -> log.error("Exception", e)
            }
        } // JMSSecurityException, JMSException, ClosedReceiveChannelException

        // notify manager if this job is still active
        if (isActive && !toManager.isClosedForSend) {
            toManager.send(Problem)
            log.error("Reported problem to manager")
        }

        log.info("@end of writeAsync - goodbye!")
    }

    abstract fun transform(event: V): Result

    companion object {

        val log = KotlinLogging.logger {  }
    }
}
