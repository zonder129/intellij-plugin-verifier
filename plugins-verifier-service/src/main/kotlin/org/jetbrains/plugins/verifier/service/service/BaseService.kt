package org.jetbrains.plugins.verifier.service.service

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.jetbrains.plugins.verifier.service.tasks.TaskManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class BaseService(private val serviceName: String,
                           private val initialDelay: Long,
                           private val period: Long,
                           private val timeUnit: TimeUnit,
                           protected val taskManager: TaskManager) {

  protected val LOG: Logger = LoggerFactory.getLogger(serviceName)

  @Volatile
  private var isServing: Boolean = false

  fun start() {
    Executors.newSingleThreadScheduledExecutor(
        ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("$serviceName-%d")
            .build()
    ).scheduleAtFixedRate({ tick() }, initialDelay, period, timeUnit)
  }

  protected fun tick() {
    if (isServing) {
      LOG.info("$serviceName is already in progress")
      return
    }

    isServing = true
    val start = System.currentTimeMillis()
    try {
      if (taskManager.isBusy()) {
        LOG.info("Task manager is full now")
        return
      }
      LOG.info("$serviceName is going to start")
      doTick()
    } catch (e: Throwable) {
      LOG.error("$serviceName failed to serve", e)
    } finally {
      val duration = System.currentTimeMillis() - start
      LOG.info("$serviceName has served in $duration ms")
      isServing = false
    }
  }

  protected abstract fun doTick()
}