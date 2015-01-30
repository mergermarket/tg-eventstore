package com.timgroup.mysqleventstore

import java.io.ByteArrayInputStream
import java.sql.{Timestamp, Connection}

import org.joda.time.{DateTimeZone, DateTime}

case class EventStream(effectiveEvents: Iterator[EffectiveEvent]) {
  def events: Iterator[SerializedEvent] = effectiveEvents.map(_.event)
}

case class SerializedEvent(eventType: String, body: Array[Byte])

case class EffectiveEvent(effectiveTimestamp: DateTime, event: SerializedEvent)

class SQLEventStore(tableName: String = "Event", now: () => DateTime) {

  def save(connection: Connection, newEvents: Seq[SerializedEvent]): Unit = {
    val effectiveTimestamp = now()
    saveRaw(connection, newEvents.map(EffectiveEvent(effectiveTimestamp, _)))
  }

  def saveRaw(connection: Connection, newEvents: Seq[EffectiveEvent]): Unit = {
    val statement = connection.prepareStatement("insert into " + tableName + "(eventType,body,effective_timestamp) values(?,?,?)")

    try {
      newEvents.foreach { effectiveEvent =>
        statement.clearParameters()
        statement.setString(1, effectiveEvent.event.eventType)
        statement.setBlob(2, new ByteArrayInputStream(effectiveEvent.event.body))
        statement.setTimestamp(3, new Timestamp(effectiveEvent.effectiveTimestamp.getMillis))
        statement.addBatch()
      }

      val batches = statement.executeBatch()

      if (batches.size != newEvents.size) {
        throw new RuntimeException("We wrote " + batches.size + " but we were supposed to write: " + newEvents.size + " events")
      }

      if (batches.filter(_ != 1).nonEmpty) {
        throw new RuntimeException("Error executing batch")
      }
    } finally {
      statement.close()
    }
  }

  def fromAll(connection: Connection): EventStream = {
    val statement = connection.prepareStatement("select effective_timestamp, eventType, body from " + tableName)

    val results = statement.executeQuery()

    try {
      val eventsIterator = new Iterator[EffectiveEvent] {
        override def hasNext = results.next()

        override def next() = EffectiveEvent(
          new DateTime(results.getTimestamp("effective_timestamp"),DateTimeZone.UTC),
          SerializedEvent(
            eventType = results.getString("eventType"),
            body = results.getBytes("body"))
        )
      }

      EventStream(eventsIterator.toList.toIterator)
    } finally {
      results.close()
    }
  }
}