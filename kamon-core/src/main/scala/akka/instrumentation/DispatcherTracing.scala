/*
 * =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package akka.instrumentation

import org.aspectj.lang.annotation._
import akka.dispatch.{ ExecutorServiceDelegate, Dispatcher, MessageDispatcher }
import kamon.metrics.{ Metrics, DispatcherMetrics }
import kamon.metrics.DispatcherMetrics.DispatcherMetricRecorder
import kamon.Kamon
import akka.actor.{ Cancellable, ActorSystemImpl }
import scala.concurrent.forkjoin.ForkJoinPool
import java.util.concurrent.ThreadPoolExecutor
import java.lang.reflect.Method
import akka.instrumentation.DispatcherMetricsCollector.DispatcherMetricsMeasurement

@Aspect
class DispatcherTracing {

  private[this] var actorSystem: ActorSystemImpl = _

  @Pointcut("execution(akka.actor.ActorSystemImpl.new(..)) && this(system)")
  def onActorSystemStartup(system: ActorSystemImpl) = {}

  @Before("onActorSystemStartup(system)")
  def beforeActorSystemStartup(system: ActorSystemImpl): Unit = {
    actorSystem = system
  }

  @Pointcut("call(* akka.dispatch.ExecutorServiceFactory.createExecutorService(..))")
  def onCreateExecutorService(): Unit = {}

  @Pointcut("cflow((execution(* akka.dispatch.MessageDispatcher.registerForExecution(..)) || execution(* akka.dispatch.MessageDispatcher.executeTask(..))) && this(dispatcher))")
  def onCflowMessageDispatcher(dispatcher: Dispatcher): Unit = {}

  @Pointcut("onCreateExecutorService() && onCflowMessageDispatcher(dispatcher)")
  def onDispatcherStartup(dispatcher: Dispatcher): Unit = {}

  @After("onDispatcherStartup(dispatcher)")
  def afterDispatcherStartup(dispatcher: MessageDispatcher): Unit = {

    val metricsExtension = Kamon(Metrics)(actorSystem)
    val metricIdentity = DispatcherMetrics(dispatcher.id)
    val dispatcherWithMetrics = dispatcher.asInstanceOf[DispatcherMessageMetrics]

    dispatcherWithMetrics.metricIdentity = metricIdentity
    dispatcherWithMetrics.dispatcherMetricsRecorder = metricsExtension.register(metricIdentity, DispatcherMetrics.Factory)

    if (dispatcherWithMetrics.dispatcherMetricsRecorder.isDefined) {
      dispatcherWithMetrics.dispatcherCollectorCancellable = metricsExtension.scheduleGaugeRecorder {
        dispatcherWithMetrics.dispatcherMetricsRecorder.map {
          dm ⇒
            val DispatcherMetricsMeasurement(maximumPoolSize, runningThreadCount, queueTaskCount, poolSize) =
              DispatcherMetricsCollector.collect(dispatcher)

            dm.maximumPoolSize.record(maximumPoolSize)
            dm.runningThreadCount.record(runningThreadCount)
            dm.queueTaskCount.record(queueTaskCount)
            dm.poolSize.record(poolSize)
        }
      }
    }
  }

  @Pointcut("execution(* akka.dispatch.MessageDispatcher.shutdown(..)) && this(dispatcher)")
  def onDispatcherShutdown(dispatcher: MessageDispatcher): Unit = {}

  @After("onDispatcherShutdown(dispatcher)")
  def afterDispatcherShutdown(dispatcher: MessageDispatcher): Unit = {
    val dispatcherWithMetrics = dispatcher.asInstanceOf[DispatcherMessageMetrics]

    dispatcherWithMetrics.dispatcherCollectorCancellable.cancel()
    Kamon(Metrics)(actorSystem).unregister(dispatcherWithMetrics.metricIdentity)
  }
}

@Aspect
class DispatcherMetricsMixin {

  @DeclareMixin("akka.dispatch.Dispatcher")
  def mixinDispatcherMetricsToMessageDispatcher: DispatcherMessageMetrics = new DispatcherMessageMetrics {}
}

trait DispatcherMessageMetrics {
  var metricIdentity: DispatcherMetrics = _
  var dispatcherMetricsRecorder: Option[DispatcherMetricRecorder] = _
  var dispatcherCollectorCancellable: Cancellable = _
}

object DispatcherMetricsCollector {

  case class DispatcherMetricsMeasurement(maximumPoolSize: Long, runningThreadCount: Long, queueTaskCount: Long, poolSize: Long)

  private def collectForkJoinMetrics(pool: ForkJoinPool): DispatcherMetricsMeasurement = {
    DispatcherMetricsMeasurement(pool.getParallelism, pool.getActiveThreadCount,
      (pool.getQueuedTaskCount + pool.getQueuedSubmissionCount), pool.getPoolSize)
  }
  private def collectExecutorMetrics(pool: ThreadPoolExecutor): DispatcherMetricsMeasurement = {
    DispatcherMetricsMeasurement(pool.getMaximumPoolSize, pool.getActiveCount, pool.getQueue.size(), pool.getPoolSize)
  }

  private val executorServiceMethod: Method = {
    // executorService is protected
    val method = classOf[Dispatcher].getDeclaredMethod("executorService")
    method.setAccessible(true)
    method
  }

  def collect(dispatcher: MessageDispatcher): DispatcherMetricsMeasurement = {
    dispatcher match {
      case x: Dispatcher ⇒ {
        val executor = executorServiceMethod.invoke(x) match {
          case delegate: ExecutorServiceDelegate ⇒ delegate.executor
          case other                             ⇒ other
        }

        executor match {
          case fjp: ForkJoinPool       ⇒ collectForkJoinMetrics(fjp)
          case tpe: ThreadPoolExecutor ⇒ collectExecutorMetrics(tpe)
          case anything                ⇒ DispatcherMetricsMeasurement(0L, 0L, 0L, 0L)
        }
      }
      case _ ⇒ new DispatcherMetricsMeasurement(0L, 0L, 0L, 0L)
    }
  }
}