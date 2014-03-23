package uk.co.scassandra.server
import akka.util.ByteString

import akka.actor.{Actor, ActorRef}
import com.typesafe.scalalogging.slf4j.Logging
import akka.io.Tcp.Write
import uk.co.scassandra.priming._
import com.batey.narinc.client.cqlmessages.response._
import scala.Some
import com.batey.narinc.client.cqlmessages.response.ReadRequestTimeout
import com.batey.narinc.client.cqlmessages.response.VoidResult
import com.batey.narinc.client.cqlmessages.response.Row
import com.batey.narinc.client.cqlmessages.response.SetKeyspace
import com.batey.narinc.client.cqlmessages.response.UnavailableException
import com.batey.narinc.client.cqlmessages.response.Rows
import scala.Some

class QueryHandler(tcpConnection: ActorRef, primedResults : PrimedResults) extends Actor with Logging {
  def receive = {
    case QueryHandlerMessages.Query(queryBody, stream) =>

      // the first 4 bytes are an int which is the length of the query
      val queryLength = queryBody.take(4).asByteBuffer.getInt
      logger.info(s"Query length is $queryLength")
      val queryText = queryBody.drop(4).take(queryLength)
      logger.info(s"Handling query |${queryText.utf8String}|")
      ActivityLog.recordQuery(queryText.utf8String)
      if (queryText.startsWith("use ")) {
        val query = queryText.utf8String
        val keyspaceName: String = query.substring(4, queryLength)
        logger.info(s"Handling use statement $query for keyspacename |$keyspaceName|")
        tcpConnection ! Write(SetKeyspace(keyspaceName, stream).serialize())
      } else {
        primedResults.get(queryText.utf8String) match {
          case Some(prime) => {
            prime.result match {
              case Success => {
                logger.info(s"Handling query ${queryText.utf8String} with rows ${prime}")
                val columnNames = prime.rows.flatMap(row => row.map( colAndValue => colAndValue._1 )).distinct
                val bytesToSend: ByteString = Rows("", "", stream, columnNames, prime.rows.map(row => Row(row))).serialize()
                logger.debug(s"Sending bytes ${bytesToSend}")
                tcpConnection ! Write(bytesToSend)
              }
              case ReadTimeout => {
                tcpConnection ! Write(ReadRequestTimeout(stream).serialize())
              }
              case Unavailable => {
                tcpConnection ! Write(UnavailableException(stream).serialize())
              }
            }
          }
          case None => {
            logger.info("Sending void result")
            tcpConnection ! Write(VoidResult(stream).serialize())
          }
          case msg @ _ => {
            logger.error(s"Got unexpected result back from primed results ${msg}")
          }
        }
      }

    case message @ _ =>
      logger.info(s"Received message $message")

  }
}

object QueryHandlerMessages {
  case class Query(queryBody: ByteString, stream: Byte)
}