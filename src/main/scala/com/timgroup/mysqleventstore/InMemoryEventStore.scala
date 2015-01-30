package com.timgroup.mysqleventstore

import java.sql.Connection

import org.joda.time.DateTime

class InMemoryEventStore extends EventStore {
  var events = Vector[EffectiveEvent]()

  override def save(connection: Connection, newEvents: Seq[SerializedEvent]): Unit = {
    events= events ++ newEvents.map(EffectiveEvent(new DateTime(), _)).zipWithIndex.map {
      case (evt, index) => evt.copy(version = Some(index + events.length + 1))
    }
  }

  override def fromAll(connection: Connection, version: Long): EventStream = {
    EventStream(events.dropWhile(_.version.get <= version).toIterator)
  }
}