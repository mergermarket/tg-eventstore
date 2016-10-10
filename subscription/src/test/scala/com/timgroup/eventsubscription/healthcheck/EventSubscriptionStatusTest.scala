package com.timgroup.eventsubscription.healthcheck

import java.time.{Clock, Instant}
import java.util.Collections

import com.timgroup.eventstore.api.LegacyPositionAdapter
import com.timgroup.tucker.info.Health.State.{healthy, ill}
import com.timgroup.tucker.info.Report
import com.timgroup.tucker.info.Status.{CRITICAL, OK, WARNING}
import org.mockito.Mockito.{mock, when}
import org.scalatest.{FunSpec, MustMatchers, OneInstancePerTest}

class EventSubscriptionStatusTest extends FunSpec with MustMatchers with OneInstancePerTest {
  val timestamp = Instant.parse("2016-02-01T00:00:00.000Z")

  val clock = mock(classOf[Clock])
  when(clock.instant()).thenReturn(timestamp)
  val status = new EventSubscriptionStatus("", clock, 123)
  val adapter = new SubscriptionListenerAdapter(LegacyPositionAdapter(0), Collections.singletonList(status))

  it("reports ill whilst initial replay is in progress") {
    status.get() must be(ill)
    status.getReport() must be(new Report(WARNING, "Awaiting events."))
  }

  it("reports healthy once initial replay is completed") {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.chaserReceived(LegacyPositionAdapter(2))
    adapter.chaserReceived(LegacyPositionAdapter(3))
    adapter.chaserUpToDate(LegacyPositionAdapter(3))

    status.get() must be(ill)
    status.getReport() must be(new Report(OK, "Stale, catching up. No events processed yet. (Stale for 0s)"))

    when(clock.instant()).thenReturn(timestamp.plusSeconds(100))
    adapter.eventProcessed(LegacyPositionAdapter(1))
    adapter.eventProcessed(LegacyPositionAdapter(2))
    adapter.eventProcessed(LegacyPositionAdapter(3))

    status.get() must be(healthy)
    status.getReport() must be(new Report(OK, "Caught up at version 3. Initial replay took 100s."))
  }

  it("reports warning if initial replay took longer than configured maximum duration") {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.chaserReceived(LegacyPositionAdapter(2))
    adapter.chaserReceived(LegacyPositionAdapter(3))
    adapter.chaserUpToDate(LegacyPositionAdapter(3))

    when(clock.instant()).thenReturn(timestamp.plusSeconds(124))
    adapter.eventProcessed(LegacyPositionAdapter(1))
    adapter.eventProcessed(LegacyPositionAdapter(2))
    adapter.eventProcessed(LegacyPositionAdapter(3))

    status.get() must be(healthy)
    status.getReport() must be(new Report(WARNING, "Caught up at version 3. Initial replay took 124s. This is longer than expected limit of 123s."))
  }

  it("reports warning if stale") {
    adapter.chaserUpToDate(LegacyPositionAdapter(5))
    adapter.eventProcessed(LegacyPositionAdapter(5))
    adapter.chaserReceived(LegacyPositionAdapter(6))
    when(clock.instant()).thenReturn(timestamp.plusSeconds(7))

    status.getReport() must be(new Report(WARNING, "Stale, catching up. Currently at version 5. (Stale for 7s)"))
  }

  it("reports critical if stale for over 30s") {
    adapter.chaserUpToDate(LegacyPositionAdapter(5))
    adapter.eventProcessed(LegacyPositionAdapter(5))
    adapter.chaserReceived(LegacyPositionAdapter(6))

    when(clock.instant()).thenReturn(timestamp.plusSeconds(31))
    status.getReport() must be(new Report(CRITICAL, "Stale, catching up. Currently at version 5. (Stale for 31s)"))
  }

  it("reports OK if stale for over 30s during catchup if under configured maximum startup duration") {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.eventProcessed(LegacyPositionAdapter(1))

    when(clock.instant()).thenReturn(timestamp.plusSeconds(123))
    status.getReport() must be(new Report(OK, "Stale, catching up. Currently at version 1. (Stale for 123s)"))
  }

  it("reports critical if stale for over configured maximum duration during catchup") {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.eventProcessed(LegacyPositionAdapter(1))

    when(clock.instant()).thenReturn(timestamp.plusSeconds(124))
    status.getReport() must be(new Report(CRITICAL, "Stale, catching up. Currently at version 1. (Stale for 124s)"))
  }

  it("reports OK once caught up again")  {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.chaserReceived(LegacyPositionAdapter(2))
    adapter.chaserUpToDate(LegacyPositionAdapter(2))
    adapter.eventProcessed(LegacyPositionAdapter(1))
    adapter.eventProcessed(LegacyPositionAdapter(2))

    status.getReport().getStatus must be(OK)
  }

  it("reports failure if subscription terminates (due to event handler failure)") {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.chaserUpToDate(LegacyPositionAdapter(1))
    adapter.eventProcessingFailed(LegacyPositionAdapter(1), new RuntimeException("Failure from handler"))

    status.getReport().getStatus must be(CRITICAL)
    status.getReport().getValue.asInstanceOf[String] must include("Event subscription terminated. Failed to process version 1: Failure from handler")
    status.getReport.getValue.asInstanceOf[String] must include("EventSubscriptionStatusTest")
  }

  it("reports failure if subscription terminates (due to deserialization failure)") {
    adapter.chaserReceived(LegacyPositionAdapter(1))
    adapter.chaserUpToDate(LegacyPositionAdapter(1))
    adapter.eventDeserializationFailed(LegacyPositionAdapter(1), new RuntimeException("Failure from deserialization"))

    status.getReport().getStatus must be(CRITICAL)
    status.getReport().getValue.asInstanceOf[String] must include("Event subscription terminated. Failed to process version 1: Failure from deserialization")
    status.getReport.getValue.asInstanceOf[String] must include("EventSubscriptionStatusTest")
  }
}
